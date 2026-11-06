package com.andresps.flights.ingestion.domain;

import com.andresps.flights.aggregation.domain.model.ProviderId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The stored snapshot of a day (spec 004, ADR-0017): the run that wrote it, the providers it came from, when, and since
 * when it is stale ({@code null} when fresh).
 */
public record DaySnapshot(
        LocalDate day, UUID runId, SortedSet<ProviderId> providers, Instant publishedAt, Instant staleSince) {

    public DaySnapshot {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(publishedAt, "publishedAt");
        providers = Collections.unmodifiableSortedSet(new TreeSet<>(providers));
    }

    public boolean stale() {
        return staleSince != null;
    }
}
