package com.andresps.flights.ingestion;

import static com.andresps.flights.ingestion.Reconciliation.assertAggregationReconciles;
import static com.andresps.flights.ingestion.Reconciliation.assertReconciles;
import static com.andresps.flights.ingestion.RecordedGdsMock.DAYS;
import static com.andresps.flights.ingestion.RecordedGdsMock.PROVIDERS;
import static com.andresps.flights.ingestion.RecordedGdsMock.stats;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.ingestion.application.port.in.StartIngestionUseCase;
import com.andresps.flights.ingestion.application.port.out.DaySnapshotStore;
import com.andresps.flights.ingestion.application.port.out.IngestionRunRepository;
import com.andresps.flights.ingestion.domain.DayOutcome;
import com.andresps.flights.ingestion.domain.DayReport;
import com.andresps.flights.ingestion.domain.IngestionReport;
import com.andresps.flights.ingestion.domain.IngestionRun;
import com.andresps.flights.ingestion.domain.IngestionTrigger;
import com.andresps.flights.ingestion.domain.RunStatus;
import com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures;
import com.andresps.flights.support.StoredData;
import com.andresps.flights.support.TestPostgres;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Spec 004 AC-24 (with AC-12, AC-13, AC-14): the whole pipeline inside the application: WireMock serving the feeds
 * recorded from gds-mock → ingestion → PostgreSQL; stored rows and the persisted report reconcile with the recorded
 * expected figures.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.main.allow-bean-definition-overriding=true",
            "aggregator.ingestion.max-concurrent-days=7",
            "aggregator.ingestion.max-concurrent-partitions=7",
            "aggregator.ingestion.run-timeout=30s",
            "aggregator.resilience.default.read-timeout=2s",
            "aggregator.resilience.default.attempt-timeout=10s",
            "aggregator.resilience.default.retry.initial-backoff=10ms"
        })
@Import(TestPostgres.class)
class IngestionPipelineIT {

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

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock clock() {
            return RecordedGdsMock.CLOCK;
        }
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop();
    }

    @Autowired
    private StartIngestionUseCase ingestions;

    @Autowired
    private IngestionRunRepository runs;

    @Autowired
    private DaySnapshotStore snapshots;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CircuitBreakerRegistry breakers;

    private StoredData stored;

    @BeforeEach
    void reset() {
        TestPostgres.truncateAll(dataSource);
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        stored = new StoredData(dataSource);
    }

    private IngestionRun runToCompletion() {
        return ingestions
                .start(IngestionTrigger.MANUAL)
                .completion()
                .orTimeout(60, TimeUnit.SECONDS)
                .join();
    }

    @Test
    @DisplayName("AC-24: stored rows per day and the persisted report reconcile with the recorded gds-mock figures")
    void pipelineReconciles() {
        IngestionRun run = runToCompletion();

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        IngestionRun persisted = runs.findById(run.id()).orElseThrow();
        assertThat(persisted).isEqualTo(run);
        IngestionReport report = persisted.report();
        assertReconciles(report, stats());
        assertThat(stored.days()).containsExactlyElementsOf(DAYS);
        for (LocalDate day : DAYS) {
            DayReport dayReport = report.day(day);
            assertAggregationReconciles(dayReport.statistics(), stats(day));
            assertThat(dayReport.publication().outcome()).isEqualTo(DayOutcome.REPLACED);
            assertThat(stored.rows(day))
                    .isEqualTo(dayReport.statistics().itinerariesProduced())
                    .isEqualTo(dayReport.publication().storedItineraries());
            StoredData.DayRow snapshot = stored.snapshot(day).orElseThrow();
            assertThat(snapshot.complete()).isTrue();
            assertThat(snapshot.runId()).isEqualTo(run.id());
            assertThat(stored.runIds(day)).containsExactly(run.id());
        }
        assertThat(report.misfiledItineraries()).isZero();
        assertThat(stored.rows()).isEqualTo(report.statistics().itinerariesProduced());
    }

    @Test
    @DisplayName("AC-12: a run deletes past days and runs older than 7 days; AC-13: the table is analyzed afterwards")
    void retentionAndStatistics() {
        LocalDate yesterday = LocalDate.now(RecordedGdsMock.CLOCK).minusDays(1);
        snapshots.replace(
                SnapshotFixtures.complete(yesterday, UUID.randomUUID(), SnapshotFixtures.itineraries(yesterday, 3)));
        IngestionRun ancient = IngestionRun.started(
                        UUID.randomUUID(),
                        IngestionTrigger.SCHEDULED,
                        RecordedGdsMock.CLOCK.instant().minus(Duration.ofDays(8)))
                .failed("old", RecordedGdsMock.CLOCK.instant().minus(Duration.ofDays(8)));
        runs.create(ancient);

        IngestionRun run = runToCompletion();

        assertThat(stored.days()).doesNotContain(yesterday).containsExactlyElementsOf(DAYS);
        assertThat(stored.snapshot(yesterday)).isEmpty();
        assertThat(runs.findById(ancient.id())).isEmpty();
        assertThat(runs.findById(run.id())).isPresent();
        assertThat(stored.lastAnalyze()).isPresent();
    }

    @Test
    @DisplayName("AC-2/AC-24: a second run replaces every day; rows always belong to a single run per day")
    void secondRunReplaces() {
        IngestionRun first = runToCompletion();
        IngestionRun second = runToCompletion();

        assertThat(second.report().days())
                .allSatisfy(day -> assertThat(day.publication().outcome()).isEqualTo(DayOutcome.REPLACED));
        DAYS.forEach(day -> assertThat(stored.runIds(day)).containsExactly(second.id()));
        assertThat(stored.rows()).isEqualTo(first.report().statistics().itinerariesProduced());
        assertThat(stored.runStatuses()).containsExactly("COMPLETED", "COMPLETED");
    }
}
