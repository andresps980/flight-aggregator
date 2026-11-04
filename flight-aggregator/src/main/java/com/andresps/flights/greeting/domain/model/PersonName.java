package com.andresps.flights.greeting.domain.model;

/**
 * Value object: a validated person name.
 *
 * <p>Domain objects are plain Java (no framework annotations) and protect their own invariants.
 */
public record PersonName(String value) {

    public static final int MAX_LENGTH = 50;

    public PersonName {
        if (value == null || value.isBlank()) {
            throw new InvalidPersonNameException("Name must not be blank");
        }
        value = value.strip();
        if (value.length() > MAX_LENGTH) {
            throw new InvalidPersonNameException("Name must be at most " + MAX_LENGTH + " characters");
        }
    }
}
