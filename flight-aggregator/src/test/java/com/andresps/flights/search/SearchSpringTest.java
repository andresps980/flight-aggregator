package com.andresps.flights.search;

import com.andresps.flights.support.MutableClock;
import com.andresps.flights.support.TestPostgres;
import com.andresps.flights.support.TestRedis;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * The search API over real HTTP, on the shared PostgreSQL and Redis containers (spec 005). Time is a
 * {@link MutableClock} the tests move by hand, starting at {@link #NOW}; ingestion never runs on its own. Each test
 * starts from empty tables, an empty cache and a closed cache breaker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestPostgres.class, TestRedis.class, SearchSpringTest.SearchTime.class})
public abstract class SearchSpringTest {

    public static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    /** Shared by the context; reset before each test. */
    protected static final MutableClock CLOCK = new MutableClock(NOW);

    @TestConfiguration(proxyBeanMethods = false)
    static class SearchTime {
        /** A distinct bean name plus {@code @Primary}; see {@code RecordedFeedsSpringTest.RecordedTime}. */
        @Bean
        @Primary
        MutableClock searchClock() {
            return CLOCK;
        }
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected MeterRegistry meters;

    @Autowired
    @Qualifier("searchCacheCircuitBreaker")
    protected CircuitBreaker cacheBreaker;

    protected SearchRows rows;
    protected RestClient http;

    @BeforeEach
    void resetSharedState() {
        CLOCK.set(NOW);
        TestPostgres.truncateAll(dataSource);
        TestRedis.flushAll();
        cacheBreaker.reset();
        rows = new SearchRows(dataSource);
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> {})
                .build();
    }

    protected ResponseEntity<JsonNode> get(String pathAndQuery) {
        return http.get().uri(pathAndQuery).retrieve().toEntity(JsonNode.class);
    }

    protected double cacheCount(String result) {
        var counter = meters.find("search.cache").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }
}
