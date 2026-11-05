package com.andresps.flights.aggregation.domain.deduplication;

import java.util.List;
import java.util.Objects;

/** Output of an aggregation: deduplicated itineraries (no guaranteed order) and the counters that explain them. */
public record AggregationResult(List<AggregatedItinerary> itineraries, AggregationStatistics statistics) {

    public AggregationResult {
        itineraries = List.copyOf(itineraries);
        Objects.requireNonNull(statistics, "statistics");
    }
}
