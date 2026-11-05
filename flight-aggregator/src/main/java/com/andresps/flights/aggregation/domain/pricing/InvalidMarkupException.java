package com.andresps.flights.aggregation.domain.pricing;

import com.andresps.flights.shared.domain.DomainValidationException;

/** A markup policy was configured with an invalid value. */
public final class InvalidMarkupException extends DomainValidationException {

    public InvalidMarkupException(String message) {
        super(message);
    }
}
