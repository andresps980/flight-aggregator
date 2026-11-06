package com.andresps.flights.ingestion.application.port.out;

import java.util.Optional;

/** The exclusive right to run an ingestion, across every instance (ADR-0019). */
public interface IngestionRunLock {

    /** Takes the lock if nobody holds it, without waiting. */
    Optional<RunLease> tryAcquire();
}
