package com.andresps.flights.ingestion.application.port.in;

/**
 * The run lost its exclusive lock (its database session ended), so another instance may be running: the run stopped
 * publishing and must be reported as failed (spec 004, AC-19).
 */
public class RunLockLostException extends RuntimeException {

    public RunLockLostException() {
        super("Ingestion lock lost: another instance may be running; no further day was published");
    }
}
