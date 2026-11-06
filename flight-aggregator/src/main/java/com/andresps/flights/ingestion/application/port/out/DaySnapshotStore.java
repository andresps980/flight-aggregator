package com.andresps.flights.ingestion.application.port.out;

import com.andresps.flights.ingestion.domain.DaySnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Day snapshots of the itinerary read model (spec 004, ADR-0017). Only one run writes at a time (ADR-0019). */
public interface DaySnapshotStore {

    Optional<DaySnapshot> find(LocalDate day);

    /**
     * Replaces the day's rows and snapshot status atomically: readers see the previous snapshot or this one, never a
     * mix. On failure nothing changes and the exception propagates.
     */
    void replace(DaySnapshotWrite write);

    /** Keeps the day's rows, marking them stale since {@code at} unless already stale; records the run that tried. */
    void markStale(LocalDate day, UUID runId, Instant at);
}
