package com.andresps.flights.ingestion.infrastructure.adapter.in.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.andresps.flights.ingestion.application.port.in.IngestionAlreadyRunningException;
import com.andresps.flights.ingestion.application.port.in.StartIngestionUseCase;
import com.andresps.flights.ingestion.application.port.in.StartedIngestion;
import com.andresps.flights.ingestion.domain.IngestionRun;
import com.andresps.flights.ingestion.domain.IngestionTrigger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Spec 004 AC-20: initial run after a delay, then a fixed delay after each run ends; skipped ticks; disabled. */
class IngestionSchedulerTest {

    private static final Duration RUN_TIME = Duration.ofMillis(150);

    private final ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
    private final List<Long> starts = new CopyOnWriteArrayList<>();
    private final List<Long> ends = new CopyOnWriteArrayList<>();
    private final List<IngestionTrigger> triggers = new CopyOnWriteArrayList<>();
    private long createdAt;

    @BeforeEach
    void startScheduler() {
        taskScheduler.initialize();
        createdAt = System.nanoTime();
    }

    @AfterEach
    void stopScheduler() {
        taskScheduler.shutdown();
    }

    /** A use case whose runs take {@link #RUN_TIME} on another thread, recording when each starts and ends. */
    private StartIngestionUseCase timedRuns() {
        return trigger -> {
            starts.add(System.nanoTime());
            triggers.add(trigger);
            IngestionRun run = IngestionRun.started(UUID.randomUUID(), trigger, Instant.now());
            CompletableFuture<IngestionRun> completion = CompletableFuture.supplyAsync(
                    () -> {
                        ends.add(System.nanoTime());
                        return run.failed("test", Instant.now());
                    },
                    CompletableFuture.delayedExecutor(RUN_TIME.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
            return new StartedIngestion(run, completion);
        };
    }

    private static Duration between(long fromNanos, long toNanos) {
        return Duration.ofNanos(toNanos - fromNanos);
    }

    @Test
    @DisplayName(
            "spec 004 AC-20: the first run starts initial-delay after start, each next one fixed-delay after the last ended")
    void initialDelayThenFixedDelayAfterCompletion() {
        var scheduler = new IngestionScheduler(
                timedRuns(), taskScheduler, new ScheduleSettings(true, Duration.ofMillis(100), Duration.ofMillis(200)));

        scheduler.start();

        await().atMost(Duration.ofSeconds(5)).until(() -> starts.size() >= 3);
        assertThat(between(createdAt, starts.get(0))).isGreaterThanOrEqualTo(Duration.ofMillis(100));
        assertThat(between(ends.get(0), starts.get(1)))
                .as("delay counted from the end of the previous run")
                .isGreaterThanOrEqualTo(Duration.ofMillis(190));
        assertThat(between(ends.get(1), starts.get(2))).isGreaterThanOrEqualTo(Duration.ofMillis(190));
        assertThat(triggers).allMatch(IngestionTrigger.SCHEDULED::equals);
    }

    @Test
    @DisplayName("spec 004 AC-20: with scheduling disabled no run starts on its own")
    void disabled() {
        var scheduler = new IngestionScheduler(
                timedRuns(), taskScheduler, new ScheduleSettings(false, Duration.ofMillis(10), Duration.ofMillis(10)));

        scheduler.start();

        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).until(starts::isEmpty);
    }

    @Test
    @DisplayName("spec 004 AC-17/AC-20: a tick while a run is in progress is skipped and scheduling continues")
    void skippedTick() {
        AtomicInteger attempts = new AtomicInteger();
        StartIngestionUseCase alwaysBusy = trigger -> {
            attempts.incrementAndGet();
            throw new IngestionAlreadyRunningException(Optional.of(UUID.randomUUID()));
        };
        var scheduler = new IngestionScheduler(
                alwaysBusy, taskScheduler, new ScheduleSettings(true, Duration.ofMillis(10), Duration.ofMillis(50)));

        scheduler.start();

        await().atMost(Duration.ofSeconds(5)).until(() -> attempts.get() >= 3);
    }

    @Test
    @DisplayName("spec 004 AC-16/AC-20: an unexpected failure of one run does not stop the schedule")
    void failureDoesNotStopScheduling() {
        AtomicInteger attempts = new AtomicInteger();
        StartIngestionUseCase failing = trigger -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("database down (test)");
        };
        var scheduler = new IngestionScheduler(
                failing, taskScheduler, new ScheduleSettings(true, Duration.ofMillis(10), Duration.ofMillis(50)));

        scheduler.start();

        await().atMost(Duration.ofSeconds(5)).until(() -> attempts.get() >= 3);
    }

    @Test
    @DisplayName("spec 004 AC-20: delays must be positive")
    void invalidSettings() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ScheduleSettings(true, Duration.ofMillis(-1), Duration.ofMillis(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initial-delay");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ScheduleSettings(true, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixed-delay");
    }
}
