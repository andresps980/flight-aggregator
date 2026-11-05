package com.andresps.flights.aggregation.domain.model;

import java.util.regex.Pattern;

/** IATA airport code: exactly 3 letters A-Z, normalised to upper case. */
public record AirportCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z]{3}");

    public AirportCode {
        value = InvalidFlightDataException.normaliseCode(value, "Airport code");
        if (!FORMAT.matcher(value).matches()) {
            throw new InvalidFlightDataException("Airport code must be 3 letters A-Z, got '" + value + "'");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
