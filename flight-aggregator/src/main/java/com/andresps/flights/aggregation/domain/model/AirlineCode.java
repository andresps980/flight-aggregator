package com.andresps.flights.aggregation.domain.model;

import java.util.regex.Pattern;

/** IATA airline designator: exactly 2 characters A-Z or 0-9 (e.g. {@code IB}, {@code U2}), upper case. */
public record AirlineCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9]{2}");

    public AirlineCode {
        value = InvalidFlightDataException.normaliseCode(value, "Airline code");
        if (!FORMAT.matcher(value).matches()) {
            throw new InvalidFlightDataException("Airline code must be 2 characters A-Z/0-9, got '" + value + "'");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
