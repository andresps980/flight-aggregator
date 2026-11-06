package com.andresps.flights.ingestion.infrastructure.adapter.in.scheduling;

import com.andresps.flights.ingestion.application.port.in.IngestionAlreadyRunningException;
import com.andresps.flights.ingestion.application.port.in.StartIngestionUseCase;
import com.andresps.flights.ingestion.application.port.in.StartedIngestion;
import com.andresps.flights.ingestion.domain.IngestionRun;
import com.andresps.flights.ingestion.domain.IngestionTrigger;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

/**
 * Starts scheduled ingestion runs (spec 004, AC-20). Each tick starts a run and waits for it to end, so the fixed
 * delay is counted from the end of the previous run and runs never pile up. A tick while a run is in progress (here
 * or in another instance) is skipped; a failure never stops the schedule. Stopping cancels the schedule and
 * interrupts a waiting tick.
 */
public final class IngestionScheduler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionScheduler.class);

    private final StartIngestionUseCase ingestions;
    private final TaskScheduler taskScheduler;
    private final ScheduleSettings settings;
    private volatile ScheduledFuture<?> schedule;

    public IngestionScheduler(
            StartIngestionUseCase ingestions, TaskScheduler taskScheduler, ScheduleSettings settings) {
        this.ingestions = Objects.requireNonNull(ingestions, "ingestions");
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "taskScheduler");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Called once the application is ready. */
    public synchronized void start() {
        if (!settings.enabled()) {
            LOG.info("Scheduled ingestion is disabled");
            return;
        }
        if (schedule == null) {
            // Wall-clock time on purpose: the task scheduler runs on real time, whatever the application Clock says.
            schedule = taskScheduler.scheduleWithFixedDelay(
                    this::tick, Instant.now().plus(settings.initialDelay()), settings.fixedDelay());
            LOG.info(
                    "Scheduled ingestion: first run in {}, then {} after each run",
                    settings.initialDelay(),
                    settings.fixedDelay());
        }
    }

    void tick() {
        try {
            StartedIngestion started = ingestions.start(IngestionTrigger.SCHEDULED);
            IngestionRun finished = started.completion().get();
            LOG.info("Scheduled ingestion {} ended {}", finished.id(), finished.status());
        } catch (IngestionAlreadyRunningException e) {
            LOG.info("Scheduled ingestion skipped: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RuntimeException e) {
            LOG.error("Scheduled ingestion failed to run; the schedule continues", e);
        }
    }

    @Override
    public synchronized void close() {
        if (schedule != null) {
            schedule.cancel(true);
            schedule = null;
        }
    }
}
