package com.example.app.shared.domain;

/** Base type for invariant violations in the domain. Mapped to HTTP 400 by the REST adapter. */
public abstract class DomainValidationException extends RuntimeException {

    protected DomainValidationException(String message) {
        super(message);
    }
}
