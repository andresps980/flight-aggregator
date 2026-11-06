package com.andresps.flights.ingestion;

import static com.andresps.flights.ingestion.RecordedGdsMock.PROVIDERS;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.andresps.flights.support.TestPostgres;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Spec 004 AC-20 in the application: with scheduling enabled (small delays), runs start on their own after the
 * application is ready and keep coming. The context is closed afterwards so its scheduler stops.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
            "aggregator.ingestion.schedule.enabled=true",
            "aggregator.ingestion.schedule.initial-delay=200ms",
            "aggregator.ingestion.schedule.fixed-delay=300ms",
            "aggregator.ingestion.horizon-days=1"
        })
@Import(TestPostgres.class)
@DirtiesContext
class IngestionSchedulingIT {

    private static final WireMockServer SERVER = startServer();

    private static WireMockServer startServer() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        RecordedGdsMock.stubAll(server);
        return server;
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        PROVIDERS.forEach(p -> registry.add("aggregator.providers." + p + ".base-url", SERVER::baseUrl));
    }

    /** Ticking, from the recorded fixtures' reference instant: run timestamps advance and feeds match the day. */
    @TestConfiguration
    static class RecordedTime {
        @Bean
        @Primary
        Clock clock() {
            return Clock.offset(Clock.systemUTC(), Duration.between(Instant.now(), RecordedGdsMock.CLOCK.instant()));
        }
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop();
    }

    @Autowired
    private DataSource dataSource;

    private List<String> scheduledRuns() {
        return JdbcClient.create(dataSource)
                .sql("SELECT status FROM ingestion_run WHERE trigger = 'SCHEDULED' ORDER BY started_at")
                .query(String.class)
                .list();
    }

    @Test
    @DisplayName("AC-20: a first scheduled run starts shortly after startup, and the next ones follow the fixed delay")
    void runsStartOnTheirOwn() {
        await().atMost(Duration.ofSeconds(30))
                .until(() ->
                        scheduledRuns().stream().filter("COMPLETED"::equals).count() >= 2);

        assertThat(scheduledRuns()).doesNotContain("FAILED", "ABORTED");
    }
}
