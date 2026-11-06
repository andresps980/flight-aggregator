package com.andresps.flights.ingestion.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec 004 AC-21: runs execute on virtual threads; closing interrupts a run in progress and waits for it. */
class IngestionRunExecutorTest {

    @Test
    @DisplayName("spec 004 AC-21: a run executes on a virtual thread")
    void virtualThreads() {
        var virtual = new CompletableFuture<Boolean>();
        try (IngestionRunExecutor executor = new IngestionRunExecutor()) {
            executor.execute(() -> virtual.complete(Thread.currentThread().isVirtual()));

            assertThat(virtual.orTimeout(5, TimeUnit.SECONDS).join()).isTrue();
        }
    }

    @Test
    @DisplayName("spec 004 AC-16: closing interrupts a run in progress and waits until it has finished")
    void closeInterruptsAndWaits() throws InterruptedException {
        var started = new CountDownLatch(1);
        var finishedAfterInterrupt = new AtomicBoolean();
        IngestionRunExecutor executor = new IngestionRunExecutor();
        executor.execute(() -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                finishedAfterInterrupt.set(true);
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        executor.close();

        assertThat(finishedAfterInterrupt).isTrue();
    }
}
