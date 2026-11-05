package com.andresps.flights.aggregation.domain.model;

import java.time.Instant;

/**
 * One flight leg. Times are absolute instants so connection times are correct across time zones. The order of
 * departure and arrival is deliberately not an invariant: it is a business rule that discards the offer with a reason.
 */
public record Segment(
        FlightNumber flightNumber, AirportCode origin, AirportCode destination, Instant departure, Instant arrival) {

    public Segment {
        InvalidFlightDataException.requireField(flightNumber, "Segment flight number");
        InvalidFlightDataException.requireField(origin, "Segment origin");
        InvalidFlightDataException.requireField(destination, "Segment destination");
        InvalidFlightDataException.requireField(departure, "Segment departure");
        InvalidFlightDataException.requireField(arrival, "Segment arrival");
        if (origin.equals(destination)) {
            throw new InvalidFlightDataException(
                    "Segment " + flightNumber + " has the same origin and destination " + origin);
        }
    }

    public AirlineCode airline() {
        return flightNumber.airline();
    }
}
