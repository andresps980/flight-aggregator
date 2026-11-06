package com.andresps.flights.ingestion;

import static com.andresps.flights.ingestion.RecordedGdsMock.PROVIDERS;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.andresps.flights.FlightAggregatorApplication;
import com.andresps.flights.support.TestPostgres;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Spec 004 AC-20 in the application: with scheduling enabled (small delays), runs start on their own after the
 * application is ready, keep coming, and stop with the application.
 *
 * <p>The application is started here with {@link SpringApplicationBuilder} instead of {@code @SpringBootTest}: a
 * context with scheduling enabled must never be cached by the test framework, or it would keep starting runs every
 * 300 ms and write into the shared database during later tests. Closing it destroys the scheduler first (cancelling
 * the schedule and interrupting a tick waiting for its run), then the run executor, which interrupts a run in progress
 * and waits for it to end; the test checks that nothing starts or stays {@code RUNNING} afterwards. Closing the
 * context does not stop the shared PostgreSQL container ({@code TestPostgres} explains why).
 */
class IngestionSchedulingIT {

    private static final WireMockServer SERVER = startServer();

    private static WireMockServer startServer() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        RecordedGdsMock.stubAll(server);
        return server;
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop();
    }

    private final JdbcClient jdbc = JdbcClient.create(TestPostgres.dataSource());

    private ConfigurableApplicationContext startApplication() {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + TestPostgres.jdbcUrl(),
                "--spring.datasource.username=" + TestPostgres.username(),
                "--spring.datasource.password=" + TestPostgres.password(),
                "--aggregator.ingestion.schedule.enabled=true",
                "--aggregator.ingestion.schedule.initial-delay=200ms",
                "--aggregator.ingestion.schedule.fixed-delay=300ms",
                "--aggregator.ingestion.horizon-days=1"));
        PROVIDERS.forEach(p -> args.add("--aggregator.providers." + p + ".base-url=" + SERVER.baseUrl()));
        return new SpringApplicationBuilder(
                        FlightAggregatorApplication.class, RecordedFeedsSpringTest.RecordedTime.class)
                .web(WebApplicationType.NONE)
                .run(args.toArray(String[]::new));
    }

    private List<String> scheduledRuns() {
        return jdbc.sql("SELECT status FROM ingestion_run WHERE trigger = 'SCHEDULED' ORDER BY started_at")
                .query(String.class)
                .list();
    }

    private long runs(String condition) {
        return jdbc.sql("SELECT count(*) FROM ingestion_run WHERE " + condition)
                .query(Long.class)
                .single();
    }

    @Test
    @DisplayName("AC-20: runs start on their own after startup, follow the fixed delay, and stop with the application")
    void scheduledRunsStartAndStopWithTheApplication() throws InterruptedException {
        TestPostgres.truncateAll(TestPostgres.dataSource());

        try (ConfigurableApplicationContext application = startApplication()) {
            await().atMost(Duration.ofSeconds(30))
                    .until(() ->
                            scheduledRuns().stream().filter("COMPLETED"::equals).count() >= 2);
            assertThat(scheduledRuns()).doesNotContain("FAILED", "ABORTED");
        }

        long runsAtClose = runs("true");
        Thread.sleep(1000); // more than three fixed delays: a live schedule would have started more runs
        assertThat(runs("true"))
                .as("no run started after the application closed")
                .isEqualTo(runsAtClose);
        assertThat(runs("status = 'RUNNING'"))
                .as("a run in progress at close ended instead of staying RUNNING")
                .isZero();
    }
}
