package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.ingestion.domain.IngestionReport;
import com.andresps.flights.ingestion.domain.IngestionRun;
import com.andresps.flights.ingestion.domain.IngestionTrigger;
import com.andresps.flights.ingestion.domain.RunStatus;
import com.andresps.flights.support.ReportFixtures;
import com.andresps.flights.support.TestPostgres;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec 004: ingestion runs stored in PostgreSQL, the report as JSONB. */
class PostgresIngestionRunRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    private final DataSource dataSource = TestPostgres.dataSource();
    private final PostgresIngestionRunRepository runs = new PostgresIngestionRunRepository(dataSource);

    @BeforeEach
    void emptyTables() {
        TestPostgres.truncateAll(dataSource);
    }

    @Test
    @DisplayName("AC-14: a RUNNING run is stored without report, then finished with status, end and the full report")
    void lifecycleRoundTrip() {
        UUID id = UUID.randomUUID();
        IngestionRun started = IngestionRun.started(id, IngestionTrigger.MANUAL, NOW);

        runs.create(started);
        assertThat(runs.findById(id)).contains(started);

        IngestionReport report = ReportFixtures.fullReport(id);
        IngestionRun finished = started.finished(report, NOW.plusSeconds(12));
        runs.update(finished);

        IngestionRun read = runs.findById(id).orElseThrow();
        assertThat(read).isEqualTo(finished);
        assertThat(read.status()).isEqualTo(RunStatus.PARTIAL);
        assertThat(read.report()).isEqualTo(report);
        assertThat(read.duration()).contains(Duration.ofSeconds(12));
    }

    @Test
    @DisplayName("AC-16: a failed run keeps its error message")
    void failedRun() {
        IngestionRun failed = IngestionRun.started(UUID.randomUUID(), IngestionTrigger.SCHEDULED, NOW)
                .failed("boom", NOW.plusSeconds(1));
        runs.create(IngestionRun.started(failed.id(), IngestionTrigger.SCHEDULED, NOW));

        runs.update(failed);

        assertThat(runs.findById(failed.id())).contains(failed);
    }

    @Test
    @DisplayName("AC-22: latest is the most recently started run; running is the RUNNING one; unknown ids are empty")
    void lookups() {
        IngestionRun older = IngestionRun.started(UUID.randomUUID(), IngestionTrigger.SCHEDULED, NOW.minusSeconds(900))
                .failed("x", NOW.minusSeconds(800));
        IngestionRun newer = IngestionRun.started(UUID.randomUUID(), IngestionTrigger.MANUAL, NOW);
        runs.create(older);
        runs.create(newer);

        assertThat(runs.findLatest()).contains(newer);
        assertThat(runs.findRunning()).contains(newer.id());
        assertThat(runs.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("AC-22/AC-23: with no runs, latest and running are empty")
    void noRuns() {
        assertThat(runs.findLatest()).isEmpty();
        assertThat(runs.findRunning()).isEmpty();
    }

    @Test
    @DisplayName("AC-15: RUNNING rows are aborted with an end instant; finished rows are untouched")
    void abortRunning() {
        IngestionRun orphan = IngestionRun.started(UUID.randomUUID(), IngestionTrigger.SCHEDULED, NOW.minusSeconds(60));
        IngestionRun done = IngestionRun.started(UUID.randomUUID(), IngestionTrigger.SCHEDULED, NOW.minusSeconds(900))
                .failed("x", NOW.minusSeconds(800));
        runs.create(orphan);
        runs.create(done);

        assertThat(runs.abortRunning(NOW)).isEqualTo(1);

        assertThat(runs.findById(orphan.id())).contains(orphan.aborted(NOW));
        assertThat(runs.findById(done.id())).contains(done);
    }

    @Test
    @DisplayName("AC-12: runs started before an instant are deleted")
    void deleteStartedBefore() {
        IngestionRun old = IngestionRun.started(
                        UUID.randomUUID(), IngestionTrigger.SCHEDULED, NOW.minus(Duration.ofDays(8)))
                .failed("x", NOW.minus(Duration.ofDays(8)));
        IngestionRun recent = IngestionRun.started(
                        UUID.randomUUID(), IngestionTrigger.SCHEDULED, NOW.minus(Duration.ofDays(6)))
                .failed("x", NOW.minus(Duration.ofDays(6)));
        runs.create(old);
        runs.create(recent);

        assertThat(runs.deleteStartedBefore(NOW.minus(Duration.ofDays(7)))).isEqualTo(1);

        assertThat(runs.findById(old.id())).isEmpty();
        assertThat(runs.findById(recent.id())).isPresent();
    }

    @Test
    @DisplayName("AC-14 (error): updating an unknown run fails instead of silently doing nothing")
    void updateUnknown() {
        IngestionRun ghost = IngestionRun.started(UUID.randomUUID(), IngestionTrigger.MANUAL, NOW)
                .failed("x", NOW);

        assertThatThrownBy(() -> runs.update(ghost)).isInstanceOf(IllegalStateException.class);
    }
}
