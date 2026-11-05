package com.andresps.flights.ingestion.infrastructure.config;

import com.andresps.flights.aggregation.domain.pricing.PercentageMarkupPolicy;
import com.andresps.flights.ingestion.application.port.in.IngestFlightsUseCase;
import com.andresps.flights.ingestion.application.port.out.DayAggregationSink;
import com.andresps.flights.ingestion.application.port.out.ProviderFeed;
import com.andresps.flights.ingestion.application.service.IngestionService;
import com.andresps.flights.ingestion.infrastructure.adapter.out.memory.InMemoryDayAggregationStore;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.FeedParser;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.HttpProviderFeed;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.ResiliencePolicies;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.ResilienceSettings;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.alpha.AlphaJsonParser;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.beta.BetaXmlParser;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.gamma.GammaCsvParser;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root of the ingestion feature: one {@link HttpProviderFeed} per configured provider, each with the parser
 * of its format and its own circuit breaker, the in-memory sink and the use case. A new provider is a new
 * {@link FeedParser} bean plus {@code aggregator.providers.<id>.base-url}; the use case doesn't change.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AggregatorProperties.class)
class IngestionConfiguration {

    @Bean
    AlphaJsonParser alphaJsonParser() {
        return new AlphaJsonParser();
    }

    @Bean
    BetaXmlParser betaXmlParser() {
        return new BetaXmlParser();
    }

    @Bean
    GammaCsvParser gammaCsvParser() {
        return new GammaCsvParser();
    }

    /** Breakers outlive runs (ADR-0014): one per provider, for the life of the application. */
    @Bean
    CircuitBreakerRegistry providerCircuitBreakers() {
        return CircuitBreakerRegistry.ofDefaults();
    }

    @Bean
    List<ProviderFeed> providerFeeds(
            List<FeedParser> parsers, AggregatorProperties properties, CircuitBreakerRegistry providerCircuitBreakers) {
        Map<String, FeedParser> adapters = parsers.stream()
                .collect(Collectors.toMap(
                        parser -> parser.provider().value(),
                        Function.identity(),
                        (a, b) -> {
                            throw new IllegalArgumentException("Two adapters for provider " + a.provider());
                        },
                        java.util.TreeMap::new)); // providers in id order, in feeds and reports
        properties.providers().keySet().forEach(id -> {
            if (!adapters.containsKey(id)) {
                throw new IllegalArgumentException(
                        "aggregator.providers." + id + " has no adapter; known providers: " + adapters.keySet());
            }
        });
        return adapters.values().stream()
                .map(parser -> {
                    String id = parser.provider().value();
                    if (!properties.providers().containsKey(id)) {
                        throw new IllegalArgumentException("aggregator.providers." + id + ".base-url is required");
                    }
                    ResilienceSettings settings = properties.resilienceFor(id);
                    return (ProviderFeed) new HttpProviderFeed(
                            parser,
                            properties.providers().get(id).baseUrl(),
                            settings,
                            providerCircuitBreakers.circuitBreaker(
                                    id,
                                    ResiliencePolicies.circuitBreakerConfig(
                                            settings.circuitBreaker(), Clock.systemUTC())));
                })
                .toList();
    }

    @Bean
    InMemoryDayAggregationStore dayAggregationStore() {
        return new InMemoryDayAggregationStore();
    }

    @Bean
    IngestFlightsUseCase ingestFlightsUseCase(
            List<ProviderFeed> providerFeeds,
            DayAggregationSink dayAggregationStore,
            Clock clock,
            AggregatorProperties properties) {
        return new IngestionService(
                providerFeeds,
                dayAggregationStore,
                new PercentageMarkupPolicy(properties.ingestion().markupPercentage()),
                clock,
                properties.ingestionSettings());
    }
}
