package com.andresps.flights.aggregation.domain.model;

import java.util.regex.Pattern;

/** Marketing flight number: airline designator plus 1-4 digits, e.g. {@code IB3456}. */
public record FlightNumber(AirlineCode airline, String number) {

    private static final Pattern NUMBER = Pattern.compile("\\d{1,4}");
    private static final int AIRLINE_LENGTH = 2;

    public FlightNumber {
        InvalidFlightDataException.requireField(airline, "Flight airline");
        InvalidFlightDataException.requireField(number, "Flight number");
        if (!NUMBER.matcher(number).matches()) {
            throw new InvalidFlightDataException("Flight number must be 1-4 digits, got '" + number + "'");
        }
    }

    /** Parses {@code "IB3456"}: the first two characters are the airline, the rest the number. */
    public static FlightNumber parse(String raw) {
        String value = InvalidFlightDataException.normaliseCode(raw, "Flight number");
        if (value.length() <= AIRLINE_LENGTH) {
            throw new InvalidFlightDataException("Flight number needs an airline code and digits, got '" + value + "'");
        }
        return new FlightNumber(new AirlineCode(value.substring(0, AIRLINE_LENGTH)), value.substring(AIRLINE_LENGTH));
    }

    @Override
    public String toString() {
        return airline.value() + number;
    }
}
