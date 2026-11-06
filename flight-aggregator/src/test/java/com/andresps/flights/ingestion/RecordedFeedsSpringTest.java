package com.andresps.flights.ingestion;

import static com.andresps.flights.ingestion.RecordedGdsMock.PROVIDERS;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.andresps.flights.support.TestPostgres;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of the Spring ITs that run the application against the feeds recorded from gds-mock. Every subclass inherits
 * the same configuration (properties, imports, dynamic properties), so they all share <em>one</em> cached Spring
 * context and one WireMock server instead of starting one each.
 *
 * <ul>
 *   <li>Timeouts and backoffs scaled to milliseconds; the breaker's open wait is long, so breaker state can be observed
 *       across runs (reset before each test).
 *   <li>A ticking clock that starts at the recordings' reference instant: the requested days match the recorded
 *       feeds, and run timestamps and durations advance as in production.
 *   <li>Before each test: WireMock serves every recorded feed again, breakers are closed and the spec 004 tables are
 *       empty, so exact counts never depend on test order. Subclasses add their own stubs in their own
 *       {@code @BeforeEach} (which runs after this one).
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "aggregator.ingestion.max-concurrent-days=7",
            "aggregator.ingestion.max-concurrent-partitions=7",
            "aggregator.ingestion.run-timeout=30s",
            "aggregator.resilience.default.connect-timeout=500ms",
            "aggregator.resilience.default.read-timeout=1s",
            "aggregator.resilience.default.attempt-timeout=5s",
            "aggregator.resilience.default.retry.initial-backoff=10ms",
            "aggregator.resilience.default.circuit-breaker.open-wait=60s"
        })
@Import({TestPostgres.class, RecordedFeedsSpringTest.RecordedTime.class})
public abstract class RecordedFeedsSpringTest {

    /** Shared by every subclass for the whole JVM (like the context that points at it). */
    protected static final WireMockServer SERVER = startServer();

    private static WireMockServer startServer() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        return server;
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        PROVIDERS.forEach(p -> registry.add("aggregator.providers." + p + ".base-url", SERVER::baseUrl));
    }

    /**
     * Ticking, from the recorded fixtures' reference instant. A distinct bean name plus {@code @Primary}: a bean named
     * {@code clock} would be overridden by the application's own definition, depending on registration order.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class RecordedTime {
        @Bean
        @Primary
        Clock recordedClock() {
            return Clock.offset(Clock.systemUTC(), Duration.between(Instant.now(), RecordedGdsMock.CLOCK.instant()));
        }
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected CircuitBreakerRegistry breakers;

    @BeforeEach
    void resetSharedState() {
        SERVER.resetAll();
        RecordedGdsMock.stubAll(SERVER);
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        TestPostgres.truncateAll(dataSource);
    }
}
