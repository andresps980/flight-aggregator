package com.andresps.flights.ingestion.domain;

/** Lifecycle status of a persisted ingestion run (spec 004). */
public enum RunStatus {
    RUNNING,
    COMPLETED,
    PARTIAL,
    /** Every provider failed, the run hit an unexpected error, or it lost its lock. */
    FAILED,
    /** Found still {@code RUNNING} after its process died. */
    ABORTED;

    public static RunStatus of(IngestionStatus status) {
        return switch (status) {
            case COMPLETED -> COMPLETED;
            case PARTIAL -> PARTIAL;
            case FAILED -> FAILED;
        };
    }
}
