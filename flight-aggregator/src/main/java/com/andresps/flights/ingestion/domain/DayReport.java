package com.andresps.flights.ingestion.domain;

import com.andresps.flights.aggregation.domain.deduplication.AggregationStatistics;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * One horizon day in the report: who contributed, who is missing, what the store did with it (spec 004) and its
 * statistics.
 */
public record DayReport(
        LocalDate day,
        SortedSet<ProviderId> contributing,
        SortedSet<ProviderId> missing,
        DayPublication publication,
        AggregationStatistics statistics) {

    public DayReport {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(statistics, "statistics");
        contributing = Collections.unmodifiableSortedSet(new TreeSet<>(contributing));
        missing = Collections.unmodifiableSortedSet(new TreeSet<>(missing));
    }

    /** Whether the run's data for the day was stored ({@code REPLACED} or {@code PUBLISHED_INCOMPLETE}). */
    public boolean published() {
        return publication.outcome().stored();
    }
}
