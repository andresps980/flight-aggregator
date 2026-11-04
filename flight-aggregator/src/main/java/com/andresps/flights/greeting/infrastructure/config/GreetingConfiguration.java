package com.andresps.flights.greeting.infrastructure.config;

import com.andresps.flights.greeting.application.port.in.GreetUseCase;
import com.andresps.flights.greeting.application.port.out.GreetingTemplateProvider;
import com.andresps.flights.greeting.application.service.GreetService;
import com.andresps.flights.greeting.infrastructure.adapter.out.config.GreetingProperties;
import com.andresps.flights.greeting.infrastructure.adapter.out.config.PropertiesGreetingTemplateProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root of the greeting feature: the only place that knows concrete implementations. */
@Configuration
@EnableConfigurationProperties(GreetingProperties.class)
class GreetingConfiguration {

    @Bean
    GreetingTemplateProvider greetingTemplateProvider(GreetingProperties properties) {
        return new PropertiesGreetingTemplateProvider(properties);
    }

    @Bean
    GreetUseCase greetUseCase(GreetingTemplateProvider greetingTemplateProvider) {
        return new GreetService(greetingTemplateProvider);
    }
}
