package com.andresps.gdsmock.inventory.infrastructure.config;

import com.andresps.gdsmock.inventory.domain.generator.WorldGenerator;
import com.andresps.gdsmock.inventory.domain.model.FxRates;
import com.andresps.gdsmock.inventory.domain.stats.WorldStatisticsCalculator;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.FeedRequestResolver;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.ProviderFormat;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.ProviderFormats;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.WorldStatisticsCache;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.format.AlphaJsonFormat;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.format.BetaXmlFormat;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.format.GammaCsvFormat;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root of the inventory feature: one bean per provider format, the generator and its collaborators. */
@Configuration(proxyBeanMethods = false)
public class InventoryConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    AlphaJsonFormat alphaJsonFormat() {
        return new AlphaJsonFormat();
    }

    @Bean
    BetaXmlFormat betaXmlFormat() {
        return new BetaXmlFormat();
    }

    @Bean
    GammaCsvFormat gammaCsvFormat() {
        return new GammaCsvFormat();
    }

    @Bean
    ProviderFormats providerFormats(List<ProviderFormat> formats) {
        return new ProviderFormats(formats);
    }

    /** Spec 008: the configured rates, served by {@code /fx/latest} and used to price non-EUR records. */
    @Bean
    FxRates fxRates(CurrencyProperties currencies) {
        return new FxRates(currencies.rates());
    }

    @Bean
    WorldGenerator worldGenerator(
            WorldProperties properties, CurrencyProperties currencies, ProviderFormats formats, Clock clock) {
        return new WorldGenerator(
                properties.toSettings(), formats.ids(), currencies.providerCurrencies(formats.ids()), clock);
    }

    @Bean
    FeedRequestResolver feedRequestResolver(ProviderFormats formats, WorldGenerator generator) {
        return new FeedRequestResolver(new LinkedHashSet<>(formats.ids()), generator::horizon);
    }

    @Bean
    WorldStatisticsCache worldStatisticsCache(WorldGenerator generator) {
        WorldStatisticsCalculator calculator = new WorldStatisticsCalculator(generator.providers());
        return new WorldStatisticsCache((horizon, day) -> calculator.calculate(
                day.map(d -> generator.itineraries(horizon, d)).orElseGet(() -> generator.itineraries(horizon))));
    }
}
