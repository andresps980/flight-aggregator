package com.andresps.flights.ingestion.application.port.out;

import com.andresps.flights.aggregation.domain.deduplication.AggregatedItinerary;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

/** A day's new snapshot: its rows (all departing on {@code day}, UTC), where they came from, and when (ADR-0017). */
public record DaySnapshotWrite(
        LocalDate day,
        UUID runId,
        List<AggregatedItinerary> itineraries,
        SortedSet<ProviderId> providers,
        SortedSet<ProviderId> missing,
        Instant publishedAt) {

    public DaySnapshotWrite {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(publishedAt, "publishedAt");
        itineraries = List.copyOf(itineraries);
        providers = Collections.unmodifiableSortedSet(new TreeSet<>(providers));
        missing = Collections.unmodifiableSortedSet(new TreeSet<>(missing));
    }

    public boolean complete() {
        return missing.isEmpty();
    }
}
