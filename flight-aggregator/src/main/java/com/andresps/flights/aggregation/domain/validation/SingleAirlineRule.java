package com.andresps.flights.aggregation.domain.validation;

import com.andresps.flights.aggregation.domain.model.Itinerary;
import com.andresps.flights.aggregation.domain.model.Segment;
import java.util.Optional;

/** All segments must be operated by the same airline (ADR-0010: no interline itineraries). */
public final class SingleAirlineRule implements ItineraryRule {

    @Override
    public Optional<DiscardReason> check(Itinerary itinerary) {
        long airlines =
                itinerary.segments().stream().map(Segment::airline).distinct().count();
        return airlines > 1 ? Optional.of(DiscardReason.MIXED_AIRLINES) : Optional.empty();
    }
}
