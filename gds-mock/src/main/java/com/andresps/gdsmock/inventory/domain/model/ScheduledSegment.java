package com.andresps.gdsmock.inventory.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One flight of an itinerary. Times are absolute; each provider renders them in its own representation.
 *
 * @param airline two-letter carrier code
 * @param flightNumber 1..9999, without carrier and without leading zeros
 */
public record ScheduledSegment(
        String airline, int flightNumber, Airport origin, Airport destination, Instant departure, Instant arrival) {

    public ScheduledSegment {
        Objects.requireNonNull(airline, "airline");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(departure, "departure");
        Objects.requireNonNull(arrival, "arrival");
    }
}
