package com.andresps.flights.ingestion.infrastructure.config;

import com.andresps.flights.aggregation.domain.pricing.PercentageMarkupPolicy;
import com.andresps.flights.ingestion.application.port.in.IngestFlightsUseCase;
import com.andresps.flights.ingestion.application.port.in.StartIngestionUseCase;
import com.andresps.flights.ingestion.application.port.out.DayAggregationSink;
import com.andresps.flights.ingestion.application.port.out.DaySnapshotStore;
import com.andresps.flights.ingestion.application.port.out.IngestionRunLock;
import com.andresps.flights.ingestion.application.port.out.IngestionRunRepository;
import com.andresps.flights.ingestion.application.port.out.ProviderFeed;
import com.andresps.flights.ingestion.application.port.out.SnapshotMaintenance;
import com.andresps.flights.ingestion.application.service.DaySnapshotPublisher;
import com.andresps.flights.ingestion.application.service.IngestionRunQueryService;
import com.andresps.flights.ingestion.application.service.IngestionRunService;
import com.andresps.flights.ingestion.application.service.IngestionService;
import com.andresps.flights.ingestion.domain.SnapshotPolicy;
import com.andresps.flights.ingestion.infrastructure.adapter.in.scheduling.IngestionScheduler;
import com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.PostgresAdvisoryRunLock;
import com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.PostgresDaySnapshotStore;
import com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.PostgresIngestionRunRepository;
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
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Composition root of the ingestion feature: one {@link HttpProviderFeed} per configured provider, each with the parser
 * of its format and its own circuit breaker; the PostgreSQL adapters (spec 004); the ingestion, run and query use cases;
 * and the scheduler. A new provider is a new
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
                        TreeMap::new)); // providers in id order, in feeds and reports
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
                                    // wall clock on purpose: tests fix the application Clock, which would freeze the
                                    // open wait
                                    ResiliencePolicies.circuitBreakerConfig(
                                            settings.circuitBreaker(), Clock.systemUTC())));
                })
                .toList();
    }

    // ----- persistence (spec 004): PostgreSQL adapters behind the outbound ports -----

    @Bean
    PostgresDaySnapshotStore daySnapshotStore(DataSource dataSource) {
        return PostgresDaySnapshotStore.withCopy(dataSource);
    }

    @Bean
    PostgresIngestionRunRepository ingestionRunRepository(DataSource dataSource) {
        return new PostgresIngestionRunRepository(dataSource);
    }

    @Bean
    PostgresAdvisoryRunLock ingestionRunLock(DataSource dataSource) {
        return new PostgresAdvisoryRunLock(dataSource);
    }

    // ----- use cases -----

    @Bean
    DaySnapshotPublisher dayAggregationSink(
            DaySnapshotStore daySnapshotStore, AggregatorProperties properties, Clock clock) {
        return new DaySnapshotPublisher(
                daySnapshotStore, new SnapshotPolicy(properties.persistence().maxStaleAge()), clock);
    }

    @Bean
    IngestFlightsUseCase ingestFlightsUseCase(
            List<ProviderFeed> providerFeeds,
            DayAggregationSink dayAggregationSink,
            Clock clock,
            AggregatorProperties properties) {
        return new IngestionService(
                providerFeeds,
                dayAggregationSink,
                new PercentageMarkupPolicy(properties.ingestion().markupPercentage()),
                clock,
                properties.ingestionSettings());
    }

    @Bean
    IngestionRunExecutor ingestionRunExecutor() {
        return new IngestionRunExecutor();
    }

    @Bean
    IngestionRunService startIngestionUseCase(
            IngestFlightsUseCase ingestFlightsUseCase,
            IngestionRunLock ingestionRunLock,
            IngestionRunRepository ingestionRunRepository,
            SnapshotMaintenance daySnapshotStore,
            IngestionRunExecutor ingestionRunExecutor,
            Clock clock,
            AggregatorProperties properties) {
        return new IngestionRunService(
                ingestFlightsUseCase,
                ingestionRunLock,
                ingestionRunRepository,
                daySnapshotStore,
                ingestionRunExecutor,
                clock,
                properties.persistence().runRetention());
    }

    @Bean
    IngestionRunQueryService getIngestionRunUseCase(IngestionRunRepository ingestionRunRepository) {
        return new IngestionRunQueryService(ingestionRunRepository);
    }

    // ----- scheduling (inbound adapter) -----

    /** A dedicated single-thread scheduler; destroyed (interrupting a waiting tick) before the use case. */
    @Bean
    ThreadPoolTaskScheduler ingestionTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ingestion-scheduler-");
        return scheduler;
    }

    @Bean
    IngestionScheduler ingestionScheduler(
            StartIngestionUseCase startIngestionUseCase,
            ThreadPoolTaskScheduler ingestionTaskScheduler,
            AggregatorProperties properties) {
        return new IngestionScheduler(
                startIngestionUseCase,
                ingestionTaskScheduler,
                properties.ingestion().schedule());
    }

    /** The first scheduled run is counted from when the application is ready to serve, not from bean creation. */
    @Bean
    ApplicationListener<ApplicationReadyEvent> startIngestionSchedule(IngestionScheduler ingestionScheduler) {
        return event -> ingestionScheduler.start();
    }
}
