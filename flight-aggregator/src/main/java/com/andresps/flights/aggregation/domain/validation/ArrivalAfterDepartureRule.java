package com.andresps.flights.aggregation.domain.validation;

import com.andresps.flights.aggregation.domain.model.Itinerary;
import java.util.Optional;

/** Every segment must arrive strictly after it departs. */
public final class ArrivalAfterDepartureRule implements ItineraryRule {

    @Override
    public Optional<DiscardReason> check(Itinerary itinerary) {
        boolean invalid = itinerary.segments().stream()
                .anyMatch(segment -> !segment.arrival().isAfter(segment.departure()));
        return invalid ? Optional.of(DiscardReason.ARRIVAL_NOT_AFTER_DEPARTURE) : Optional.empty();
    }
}
