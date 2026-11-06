package com.andresps.flights.ingestion.domain;

/** What storing a day did to its snapshot (spec 004, AC-7; ADR-0017). */
public enum DayOutcome {
    /** Every provider contributed; the day's snapshot was replaced. */
    REPLACED,
    /** Written with missing providers (no previous snapshot, a less complete one, or one stale for too long). */
    PUBLISHED_INCOMPLETE,
    /** The run's data for the day was less complete than the stored snapshot, which was kept and marked stale. */
    KEPT_STALE,
    /** Every provider failed and there was no snapshot to keep. */
    NO_DATA,
    /** The write failed and was rolled back; the previous snapshot, if any, is intact. */
    WRITE_FAILED;

    /** Whether the day's new data was stored. */
    public boolean stored() {
        return this == REPLACED || this == PUBLISHED_INCOMPLETE;
    }
}
