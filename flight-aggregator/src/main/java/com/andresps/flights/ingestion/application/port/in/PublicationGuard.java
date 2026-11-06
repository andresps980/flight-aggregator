package com.andresps.flights.ingestion.application.port.in;

/** Whether the run may still publish: false once it has lost its exclusive lock (spec 004, AC-19; ADR-0019). */
@FunctionalInterface
public interface PublicationGuard {

    /** For callers that do not lock, such as tests. */
    PublicationGuard ALWAYS = () -> true;

    boolean mayPublish();
}
