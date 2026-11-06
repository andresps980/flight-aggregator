package com.andresps.flights.ingestion.application.service;

import com.andresps.flights.ingestion.application.port.out.IngestionRunLock;
import com.andresps.flights.ingestion.application.port.out.RunLease;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory lock with the advisory lock's semantics: one holder at a time, released by closing the lease. */
final class FakeRunLock implements IngestionRunLock {

    private final AtomicBoolean taken = new AtomicBoolean();
    private final AtomicInteger acquisitions = new AtomicInteger();
    private volatile Lease current;

    @Override
    public Optional<RunLease> tryAcquire() {
        if (!taken.compareAndSet(false, true)) {
            return Optional.empty();
        }
        acquisitions.incrementAndGet();
        current = new Lease();
        return Optional.of(current);
    }

    /** Another instance holds the lock. */
    void takenElsewhere() {
        taken.set(true);
    }

    /** The lock connection drops: the lease is no longer held, but stays open until the run closes it. */
    void loseCurrentLease() {
        current.lost = true;
    }

    boolean isTaken() {
        return taken.get();
    }

    int acquisitions() {
        return acquisitions.get();
    }

    private final class Lease implements RunLease {

        private volatile boolean lost;
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public boolean isHeld() {
            return !lost && !closed.get();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                taken.set(false);
            }
        }
    }
}
