package com.andresps.flights.ingestion.application.service;

import static com.andresps.flights.aggregation.domain.FlightFixtures.ALPHA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.BETA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.aggregation.domain.deduplication.AggregationStatistics;
import com.andresps.flights.ingestion.application.port.in.IngestFlightsUseCase;
import com.andresps.flights.ingestion.application.port.in.IngestionAlreadyRunningException;
import com.andresps.flights.ingestion.application.port.in.RunLockLostException;
import com.andresps.flights.ingestion.application.port.in.StartedIngestion;
import com.andresps.flights.ingestion.domain.CircuitState;
import com.andresps.flights.ingestion.domain.FailureKind;
import com.andresps.flights.ingestion.domain.IngestionReport;
import com.andresps.flights.ingestion.domain.IngestionRun;
import com.andresps.flights.ingestion.domain.IngestionTrigger;
import com.andresps.flights.ingestion.domain.PartitionFailure;
import com.andresps.flights.ingestion.domain.ProviderReport;
import com.andresps.flights.ingestion.domain.RunStatus;
import com.andresps.flights.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec 004: run lifecycle around the ingestion use case: lock, persisted run, retention, statistics. */
class IngestionRunServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final Executor SAME_THREAD = Runnable::run;

    private final MutableClock clock = new MutableClock(NOW);
    private final FakeRunLock lock = new FakeRunLock();
    private final InMemoryRunRepository runs = new InMemoryRunRepository();
    private final InMemoryDaySnapshotStore maintenance = new InMemoryDaySnapshotStore();
    private final List<String> events = new ArrayList<>();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void stopBackground() {
        background.shutdownNow();
    }

    private IngestionRunService service(IngestFlightsUseCase ingestion, Executor executor) {
        return new IngestionRunService(ingestion, lock, runs, maintenance, executor, clock, RETENTION);
    }

    private static IngestionReport report(UUID runId, ProviderReport... providers) {
        return new IngestionReport(
                runId, NOW, NOW.plusSeconds(5), List.of(providers), List.of(), AggregationStatistics.empty());
    }

    private static ProviderReport completed() {
        return new ProviderReport(ALPHA, 1, 1, List.of(), 1, Duration.ofSeconds(1), CircuitState.CLOSED, 10, Map.of());
    }

    private static ProviderReport failed() {
        return new ProviderReport(
                BETA,
                1,
                0,
                List.of(new PartitionFailure(LocalDate.parse("2026-11-06"), FailureKind.SERVER_ERROR, 3, "500")),
                3,
                Duration.ofSeconds(1),
                CircuitState.CLOSED,
                0,
                Map.of());
    }

    @Test
    @DisplayName(
            "spec 004 AC-14: the run is stored RUNNING (trigger, start) before ingesting, then with status, end and report")
    void runLifecycleIsPersisted() {
        AtomicReference<IngestionRun> seenDuringIngestion = new AtomicReference<>();
        IngestFlightsUseCase ingestion = (runId, guard) -> {
            seenDuringIngestion.set(runs.findById(runId).orElseThrow());
            clock.advance(Duration.ofSeconds(5));
            return report(runId, completed());
        };

        StartedIngestion started = service(ingestion, SAME_THREAD).start(IngestionTrigger.MANUAL);

        assertThat(started.run().status()).isEqualTo(RunStatus.RUNNING);
        assertThat(seenDuringIngestion.get()).isEqualTo(started.run());
        assertThat(seenDuringIngestion.get().trigger()).isEqualTo(IngestionTrigger.MANUAL);
        assertThat(seenDuringIngestion.get().startedAt()).isEqualTo(NOW);
        IngestionRun finished = runs.findById(started.run().id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(finished.finishedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(finished.report().runId()).isEqualTo(started.run().id());
        assertThat(started.completion()).isCompletedWithValue(finished);
    }

    @Test
    @DisplayName("spec 004 AC-14: the stored status follows the report (PARTIAL, FAILED)")
    void statusFollowsReport() {
        StartedIngestion partial = service((runId, guard) -> report(runId, completed(), failed()), SAME_THREAD)
                .start(IngestionTrigger.SCHEDULED);
        StartedIngestion allFailed =
                service((runId, guard) -> report(runId, failed()), SAME_THREAD).start(IngestionTrigger.SCHEDULED);

        assertThat(partial.completion().join().status()).isEqualTo(RunStatus.PARTIAL);
        assertThat(allFailed.completion().join().status()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    @DisplayName("spec 004 AC-15: RUNNING rows left by a dead process are marked ABORTED once the lock is acquired")
    void orphansAreAborted() {
        IngestionRun orphan =
                IngestionRun.started(UUID.randomUUID(), IngestionTrigger.SCHEDULED, NOW.minusSeconds(3600));
        runs.put(orphan);

        StartedIngestion started = service((runId, guard) -> report(runId, completed()), SAME_THREAD)
                .start(IngestionTrigger.MANUAL);

        IngestionRun aborted = runs.findById(orphan.id()).orElseThrow();
        assertThat(aborted.status()).isEqualTo(RunStatus.ABORTED);
        assertThat(aborted.finishedAt()).isEqualTo(NOW);
        assertThat(runs.findById(started.run().id()).orElseThrow().status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    @DisplayName(
            "spec 004 AC-16 (error): an unexpected exception stores the run FAILED with the message and releases the lock")
    void unexpectedFailure() {
        IngestionRunService service = service(
                (runId, guard) -> {
                    throw new IllegalStateException("boom (test)");
                },
                SAME_THREAD);

        IngestionRun failedRun =
                service.start(IngestionTrigger.MANUAL).completion().join();

        assertThat(failedRun.status()).isEqualTo(RunStatus.FAILED);
        assertThat(failedRun.failure())
                .as("names the error type, never its message (it may carry SQL or hosts)")
                .contains("IllegalStateException")
                .doesNotContain("boom");
        assertThat(failedRun.finishedAt()).isNotNull();
        assertThat(lock.isTaken()).isFalse();
        assertThat(service.start(IngestionTrigger.MANUAL).completion().join().status())
                .as("the next run can start")
                .isEqualTo(RunStatus.FAILED);
        assertThat(lock.acquisitions()).isEqualTo(2);
    }

    @Test
    @DisplayName("spec 004 AC-17: with the lock held elsewhere no run starts and the running id is reported")
    void lockHeldElsewhere() {
        IngestionRun running =
                IngestionRun.started(UUID.randomUUID(), IngestionTrigger.SCHEDULED, NOW.minusSeconds(10));
        runs.put(running);
        lock.takenElsewhere();
        IngestionRunService service = service(
                (runId, guard) -> {
                    events.add("ingested");
                    return report(runId, completed());
                },
                SAME_THREAD);

        assertThatThrownBy(() -> service.start(IngestionTrigger.MANUAL))
                .isInstanceOfSatisfying(
                        IngestionAlreadyRunningException.class,
                        e -> assertThat(e.runningId()).contains(running.id()));
        assertThat(events).isEmpty();
        assertThat(runs.size()).isEqualTo(1);
        assertThat(runs.findById(running.id()).orElseThrow().status())
                .as("not aborted: it is alive")
                .isEqualTo(RunStatus.RUNNING);
    }

    @Test
    @DisplayName("spec 004 AC-17/AC-21: a run executes asynchronously; a second start while it runs is rejected")
    void secondStartWhileRunning() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        IngestionRunService service = service(
                (runId, guard) -> {
                    await(release);
                    return report(runId, completed());
                },
                background);

        StartedIngestion first = service.start(IngestionTrigger.MANUAL);

        assertThat(first.completion()).isNotDone();
        assertThatThrownBy(() -> service.start(IngestionTrigger.SCHEDULED))
                .isInstanceOfSatisfying(
                        IngestionAlreadyRunningException.class,
                        e -> assertThat(e.runningId()).contains(first.run().id()));
        release.countDown();
        assertThat(first.completion().orTimeout(5, TimeUnit.SECONDS).join().status())
                .isEqualTo(RunStatus.COMPLETED);
        assertThat(lock.isTaken()).isFalse();
    }

    @Test
    @DisplayName("spec 004 AC-18/AC-19: the guard handed to the ingestion follows the lease; a lost lock fails the run")
    void lockLost() {
        AtomicReference<Boolean> before = new AtomicReference<>();
        AtomicReference<Boolean> after = new AtomicReference<>();
        IngestFlightsUseCase ingestion = (runId, guard) -> {
            before.set(guard.mayPublish());
            lock.loseCurrentLease();
            after.set(guard.mayPublish());
            throw new RunLockLostException();
        };

        IngestionRun run = service(ingestion, SAME_THREAD)
                .start(IngestionTrigger.SCHEDULED)
                .completion()
                .join();

        assertThat(before.get()).isTrue();
        assertThat(after.get()).isFalse();
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.failure()).containsIgnoringCase("lock lost");
        assertThat(lock.isTaken()).as("lease closed").isFalse();
    }

    @Test
    @DisplayName("spec 004 AC-12: a run deletes days before today and runs started more than the retention ago")
    void retention() {
        IngestionRun old = IngestionRun.started(
                        UUID.randomUUID(), IngestionTrigger.SCHEDULED, NOW.minus(Duration.ofDays(8)))
                .failed("old", NOW.minus(Duration.ofDays(8)));
        IngestionRun recent = IngestionRun.started(
                        UUID.randomUUID(), IngestionTrigger.SCHEDULED, NOW.minus(Duration.ofDays(6)))
                .failed("recent", NOW.minus(Duration.ofDays(6)));
        runs.put(old);
        runs.put(recent);
        AtomicReference<List<LocalDate>> deletedBeforeIngesting = new AtomicReference<>();

        service(
                        (runId, guard) -> {
                            deletedBeforeIngesting.set(maintenance.deletedBefore());
                            return report(runId, completed());
                        },
                        SAME_THREAD)
                .start(IngestionTrigger.SCHEDULED);

        assertThat(deletedBeforeIngesting.get()).containsExactly(LocalDate.parse("2026-11-05"));
        assertThat(runs.findById(old.id())).isEmpty();
        assertThat(runs.findById(recent.id())).isPresent();
    }

    @Test
    @DisplayName("spec 004 AC-13: statistics are refreshed once after the run's writes, also when the run fails")
    void statisticsRefreshed() {
        service(
                        (runId, guard) -> {
                            assertThat(maintenance.statisticsRefreshes()).isZero();
                            return report(runId, completed());
                        },
                        SAME_THREAD)
                .start(IngestionTrigger.SCHEDULED);
        assertThat(maintenance.statisticsRefreshes()).isEqualTo(1);

        service(
                        (runId, guard) -> {
                            throw new IllegalStateException("boom (test)");
                        },
                        SAME_THREAD)
                .start(IngestionTrigger.SCHEDULED);
        assertThat(maintenance.statisticsRefreshes()).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "spec 004 AC-16 (error): if the run cannot be scheduled, the lock is released and the run is stored FAILED")
    void executorRejects() {
        Executor rejecting = task -> {
            throw new RejectedExecutionException("shutting down (test)");
        };

        assertThatThrownBy(() -> service((runId, guard) -> report(runId, completed()), rejecting)
                        .start(IngestionTrigger.MANUAL))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(lock.isTaken()).isFalse();
        assertThat(runs.findLatest().orElseThrow().status()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    @DisplayName("spec 004 AC-16: a run interrupted at shutdown still stores its final state and releases the lock")
    void interruptedRunStoresItsState() {
        IngestionRun run = service(
                        (runId, guard) -> {
                            Thread.currentThread().interrupt(); // as IngestionRunExecutor.close() does
                            throw new IllegalStateException("Interrupted while waiting for the ingestion to finish");
                        },
                        SAME_THREAD)
                .start(IngestionTrigger.SCHEDULED)
                .completion()
                .join();

        assertThat(Thread.interrupted())
                .as("the interrupt is restored afterwards")
                .isTrue();
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo(RunStatus.FAILED);
        assertThat(runs.updatedWhileInterrupted())
                .as("bookkeeping runs with the interrupt flag cleared")
                .isFalse();
        assertThat(lock.isTaken()).isFalse();
    }

    @Test
    @DisplayName("spec 004 AC-12: a retention failure is logged and the run still ingests")
    void retentionFailureDoesNotStopTheRun() {
        maintenance.failRetention();

        IngestionRun run = service((runId, guard) -> report(runId, completed()), SAME_THREAD)
                .start(IngestionTrigger.SCHEDULED)
                .completion()
                .join();

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released (test)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
