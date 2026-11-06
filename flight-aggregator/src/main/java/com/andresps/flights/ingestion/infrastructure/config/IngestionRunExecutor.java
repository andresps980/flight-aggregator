package com.andresps.flights.ingestion.infrastructure.config;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs ingestions on virtual threads (spec 004, AC-21). On shutdown a run still going is interrupted (it ends
 * {@code FAILED} and releases its lock) and awaited for a bounded time, so it never outlives the application context,
 * nor writes after the data source closed.
 */
final class IngestionRunExecutor implements Executor, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionRunExecutor.class);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(30);

    private final ExecutorService virtualThreads = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ingestion-run-", 0).factory());

    @Override
    public void execute(Runnable task) {
        virtualThreads.execute(task);
    }

    @Override
    public void close() {
        virtualThreads.shutdownNow();
        try {
            if (!virtualThreads.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warn("An ingestion run did not stop within {}", SHUTDOWN_WAIT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
