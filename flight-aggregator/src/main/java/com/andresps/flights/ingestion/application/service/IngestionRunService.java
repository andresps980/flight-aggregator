package com.andresps.flights.ingestion.application.service;

import com.andresps.flights.ingestion.application.port.in.IngestFlightsUseCase;
import com.andresps.flights.ingestion.application.port.in.IngestionAlreadyRunningException;
import com.andresps.flights.ingestion.application.port.in.RunLockLostException;
import com.andresps.flights.ingestion.application.port.in.StartIngestionUseCase;
import com.andresps.flights.ingestion.application.port.in.StartedIngestion;
import com.andresps.flights.ingestion.application.port.out.IngestionRunLock;
import com.andresps.flights.ingestion.application.port.out.IngestionRunRepository;
import com.andresps.flights.ingestion.application.port.out.RunLease;
import com.andresps.flights.ingestion.application.port.out.SnapshotMaintenance;
import com.andresps.flights.ingestion.domain.IngestionReport;
import com.andresps.flights.ingestion.domain.IngestionRun;
import com.andresps.flights.ingestion.domain.IngestionTrigger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts ingestion runs (spec 004): takes the cross-instance lock (ADR-0019), marks runs orphaned by a dead process
 * {@code ABORTED}, stores the new run as {@code RUNNING} and executes it on the injected executor. The run applies the
 * retention (best effort), ingests with a guard that follows the lock, refreshes the planner statistics, stores its
 * final state and releases the lock, whatever happens.
 */
public final class IngestionRunService implements StartIngestionUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionRunService.class);

    private final IngestFlightsUseCase ingestion;
    private final IngestionRunLock lock;
    private final IngestionRunRepository runs;
    private final SnapshotMaintenance maintenance;
    private final Executor executor;
    private final Clock clock;
    private final Duration runRetention;

    public IngestionRunService(
            IngestFlightsUseCase ingestion,
            IngestionRunLock lock,
            IngestionRunRepository runs,
            SnapshotMaintenance maintenance,
            Executor executor,
            Clock clock,
            Duration runRetention) {
        this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
        this.lock = Objects.requireNonNull(lock, "lock");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runRetention = Objects.requireNonNull(runRetention, "runRetention");
    }

    @Override
    public StartedIngestion start(IngestionTrigger trigger) {
        RunLease lease = lock.tryAcquire().orElseThrow(() -> new IngestionAlreadyRunningException(runs.findRunning()));
        IngestionRun run;
        try {
            // Holding the lock, any RUNNING row belongs to a process that died mid-run.
            int aborted = runs.abortRunning(now());
            if (aborted > 0) {
                LOG.warn("Marked {} orphaned ingestion runs ABORTED", aborted);
            }
            run = IngestionRun.started(UUID.randomUUID(), trigger, now());
            runs.create(run);
        } catch (RuntimeException e) {
            lease.close();
            throw e;
        }
        CompletableFuture<IngestionRun> completion = new CompletableFuture<>();
        try {
            executor.execute(() -> execute(run, lease, completion));
        } catch (RuntimeException e) {
            try {
                runs.update(run.failed(
                        "The run could not be scheduled (" + e.getClass().getSimpleName() + ")", now()));
            } finally {
                lease.close();
            }
            throw e;
        }
        LOG.info("Ingestion {} started ({})", run.id(), trigger);
        return new StartedIngestion(run, completion);
    }

    /**
     * Always refreshes the statistics, stores the final state, releases the lock and completes the run, even when an
     * {@link Error} escapes. A run interrupted at shutdown still has its interrupt flag set here; it is cleared for this
     * bookkeeping (an interrupted thread would abort the database calls) and restored afterwards.
     */
    private void execute(IngestionRun run, RunLease lease, CompletableFuture<IngestionRun> completion) {
        IngestionRun finished = run;
        try {
            finished = ingest(run, lease);
        } catch (Error e) {
            LOG.error("Ingestion {} died with {}", run.id(), e.toString());
            finished = run.failed(unexpected(e), now());
            throw e;
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                refreshStatistics(run.id());
                runs.update(finished);
            } catch (RuntimeException e) {
                LOG.error("Ingestion {} ended {} but its state could not be stored", run.id(), finished.status(), e);
            } finally {
                lease.close();
                completion.complete(finished);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private IngestionRun ingest(IngestionRun run, RunLease lease) {
        try {
            applyRetention(run.id());
            IngestionReport report = ingestion.ingest(run.id(), lease::isHeld);
            return run.finished(report, now());
        } catch (RunLockLostException e) {
            return run.failed(e.getMessage(), now());
        } catch (RuntimeException e) {
            LOG.error("Ingestion {} failed unexpectedly", run.id(), e);
            return run.failed(unexpected(e), now());
        }
    }

    /**
     * The stored and served failure names the error type only: exception messages can carry SQL, host names or ports.
     * The details are in the log, under the run id.
     */
    private static String unexpected(Throwable e) {
        return "Unexpected error (" + e.getClass().getSimpleName() + "); details are logged with the run id";
    }

    /** PostgreSQL stores microseconds: instants are truncated so that a stored run reads back equal. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** Best effort: past days and old runs are harmless for one more run, so a failure here never stops ingestion. */
    private void applyRetention(UUID runId) {
        try {
            LocalDate today = LocalDate.ofInstant(now(), ZoneOffset.UTC);
            maintenance.deleteDaysBefore(today);
            int deleted = runs.deleteStartedBefore(now().minus(runRetention));
            if (deleted > 0) {
                LOG.info("Deleted {} ingestion runs older than {}", deleted, runRetention);
            }
        } catch (RuntimeException e) {
            LOG.warn("Ingestion {}: retention failed; it will be retried by the next run", runId, e);
        }
    }

    private void refreshStatistics(UUID runId) {
        try {
            maintenance.refreshStatistics();
        } catch (RuntimeException e) {
            LOG.warn("Ingestion {}: refreshing the planner statistics failed", runId, e);
        }
    }
}
