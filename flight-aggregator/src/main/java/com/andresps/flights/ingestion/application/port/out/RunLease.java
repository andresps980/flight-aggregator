package com.andresps.flights.ingestion.application.port.out;

/** A held run lock (ADR-0019). Closing it releases the lock; closing twice is harmless. */
public interface RunLease extends AutoCloseable {

    /** Whether the lock is still held; false once released or once its holder's session was lost. */
    boolean isHeld();

    @Override
    void close();
}
