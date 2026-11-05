package com.andresps.flights.aggregation.domain.model;

/**
 * Deterministic identity of an itinerary across providers, e.g.
 * {@code IB3456:MAD-BCN@2026-11-10T08:00:00Z|IB3457:BCN-LHR@2026-11-10T10:30:00Z}. Flight numbers carry the airline;
 * provider, price and arrival times are not part of it. Created by {@link Itinerary#key()}.
 */
public record ItineraryKey(String value) {

    static final String SEGMENT_SEPARATOR = "|";

    public ItineraryKey {
        InvalidFlightDataException.requireField(value, "Itinerary key");
        if (value.isBlank()) {
            throw new InvalidFlightDataException("Itinerary key must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
