package com.andresps.flights.aggregation.domain.model;

import com.andresps.flights.shared.domain.DomainValidationException;
import java.util.Locale;

/**
 * Malformed flight data: a value that can never form a valid offer (e.g. a 2-letter airport code, a negative price).
 * Distinct from a business discard (see {@code DiscardReason}), which applies to well-formed but unsellable itineraries.
 */
public final class InvalidFlightDataException extends DomainValidationException {

    public InvalidFlightDataException(String message) {
        super(message);
    }

    static <T> T requireField(T value, String field) {
        if (value == null) {
            throw new InvalidFlightDataException(field + " is required");
        }
        return value;
    }

    /** Trims and upper-cases a code, rejecting {@code null}. */
    static String normaliseCode(String raw, String field) {
        return requireField(raw, field).strip().toUpperCase(Locale.ROOT);
    }
}
