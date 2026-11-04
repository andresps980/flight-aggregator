package com.example.app.greeting.domain.model;

import com.example.app.shared.domain.DomainValidationException;

public class InvalidPersonNameException extends DomainValidationException {

    public InvalidPersonNameException(String message) {
        super(message);
    }
}
