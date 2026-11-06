package com.andresps.flights.ingestion;

import static com.andresps.flights.ingestion.Reconciliation.assertAggregationReconciles;
import static com.andresps.flights.ingestion.Reconciliation.assertReconciles;
import static com.andresps.flights.ingestion.RecordedGdsMock.DAYS;
import static com.andresps.flights.ingestion.RecordedGdsMock.stats;
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
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Spec 004 AC-24 (with AC-12, AC-13, AC-14): the whole pipeline inside the application: WireMock serving the feeds
 * recorded from gds-mock → ingestion → PostgreSQL; stored rows and the persisted report reconcile with the recorded
 * expected figures.
 */
class IngestionPipelineIT extends RecordedFeedsSpringTest {

    @Autowired
    private StartIngestionUseCase ingestions;

    @Autowired
    private IngestionRunRepository runs;

    @Autowired
    private DaySnapshotStore snapshots;

    private StoredData stored;

    @BeforeEach
    void storedData() {
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
        LocalDate yesterday = LocalDate.ofInstant(RecordedGdsMock.CLOCK.instant(), java.time.ZoneOffset.UTC)
                .minusDays(1);
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
