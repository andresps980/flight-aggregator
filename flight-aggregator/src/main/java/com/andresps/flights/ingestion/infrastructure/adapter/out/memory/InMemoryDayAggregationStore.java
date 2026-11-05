package com.andresps.flights.ingestion.infrastructure.adapter.out.memory;

import com.andresps.flights.ingestion.application.port.out.DayAggregationSink;
import com.andresps.flights.ingestion.domain.DayAggregation;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Keeps the latest published aggregation of each day, in memory, until the PostgreSQL adapter replaces it (next spec).
 * A new publication of a day replaces its snapshot: keeping a missing provider's previous data is the persistence
 * spec's requirement, not this placeholder's. Holds every published itinerary, i.e. the whole world (SOLUTION.md).
 */
public final class InMemoryDayAggregationStore implements DayAggregationSink {

    private final ConcurrentSkipListMap<LocalDate, DayAggregation> days = new ConcurrentSkipListMap<>();

    @Override
    public void publish(DayAggregation day) {
        days.put(day.day(), day);
    }

    /** Published days, in order. */
    public List<LocalDate> days() {
        return List.copyOf(days.keySet());
    }

    public Optional<DayAggregation> day(LocalDate day) {
        return Optional.ofNullable(days.get(day));
    }

    public void clear() {
        days.clear();
    }
}
