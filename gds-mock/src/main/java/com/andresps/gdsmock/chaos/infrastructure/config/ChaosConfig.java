package com.andresps.gdsmock.chaos.infrastructure.config;

import com.andresps.gdsmock.chaos.domain.ChaosPolicy;
import com.andresps.gdsmock.chaos.domain.ChaosSettings;
import com.andresps.gdsmock.chaos.domain.OutageRegistry;
import com.andresps.gdsmock.chaos.infrastructure.adapter.in.web.ChaosInterceptor;
import com.andresps.gdsmock.chaos.infrastructure.adapter.in.web.Sleeper;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.FeedRequestResolver;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.ProviderFormats;
import com.andresps.gdsmock.shared.domain.ProviderId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Composition root of the chaos feature: settings per provider, the policy and the interceptor on provider feeds. */
@Configuration(proxyBeanMethods = false)
public class ChaosConfig {

    @Bean
    OutageRegistry outageRegistry() {
        return new OutageRegistry();
    }

    @Bean
    ChaosPolicy chaosPolicy(ChaosProperties properties, ProviderFormats formats, OutageRegistry outages) {
        Map<ProviderId, ChaosSettings> settings = new LinkedHashMap<>();
        formats.ids().forEach(id -> settings.put(id, properties.settingsFor(id.value())));
        return new ChaosPolicy(settings, outages, ThreadLocalRandom::current);
    }

    @Bean
    Sleeper sleeper() {
        return Thread::sleep;
    }

    @Bean
    ChaosInterceptor chaosInterceptor(ChaosPolicy policy, Sleeper sleeper, FeedRequestResolver resolver) {
        return new ChaosInterceptor(policy, sleeper, resolver);
    }

    @Bean
    WebMvcConfigurer chaosOnProviderFeeds(ChaosInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns("/*/flights");
            }
        };
    }
}
