package com.andresps.flights.search.domain;

import com.andresps.flights.shared.domain.DomainValidationException;

/** A search parameter or cursor that breaks the API contract (spec 005 AC-12 – AC-16). Mapped to {@code 400}. */
public final class InvalidSearchRequestException extends DomainValidationException {

    public InvalidSearchRequestException(String message) {
        super(message);
    }
}
