package com.andresps.flights.ingestion.domain;

/** Whether a publication replaces a day's snapshot or leaves it in place, stale (ADR-0017). */
public enum SnapshotDecision {
    REPLACE,
    KEEP_STALE
}
