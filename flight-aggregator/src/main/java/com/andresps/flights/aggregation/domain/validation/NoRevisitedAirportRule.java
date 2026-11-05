package com.andresps.flights.aggregation.domain.validation;

import com.andresps.flights.aggregation.domain.model.AirportCode;
import com.andresps.flights.aggregation.domain.model.Itinerary;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** A one-way itinerary must not pass through the same airport twice (e.g. MAD-BCN-MAD-LHR). */
public final class NoRevisitedAirportRule implements ItineraryRule {

    @Override
    public Optional<DiscardReason> check(Itinerary itinerary) {
        List<AirportCode> visited = itinerary.visitedAirports();
        boolean revisits = new HashSet<>(visited).size() < visited.size();
        return revisits ? Optional.of(DiscardReason.REVISITED_AIRPORT) : Optional.empty();
    }
}
