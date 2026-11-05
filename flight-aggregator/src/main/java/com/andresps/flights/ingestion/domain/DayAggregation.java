package com.andresps.flights.ingestion.domain;

import com.andresps.flights.aggregation.domain.deduplication.AggregationResult;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The aggregation of one horizon day (ADR-0016), as published to the result port. {@code missing} names the providers
 * whose partition failed, so a store can keep their previous data for the day instead of wiping it (spec 003,
 * requirements for the persistence spec).
 */
public record DayAggregation(
        LocalDate day, AggregationResult result, SortedSet<ProviderId> contributing, SortedSet<ProviderId> missing) {

    public DayAggregation {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(result, "result");
        contributing = Collections.unmodifiableSortedSet(new TreeSet<>(contributing));
        missing = Collections.unmodifiableSortedSet(new TreeSet<>(missing));
    }
}
