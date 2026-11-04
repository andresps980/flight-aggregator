package com.andresps.flights.greeting.domain.model;

import com.andresps.flights.shared.domain.DomainValidationException;

public class InvalidPersonNameException extends DomainValidationException {

    public InvalidPersonNameException(String message) {
        super(message);
    }
}
