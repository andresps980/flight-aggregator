package com.andresps.flights.shared.domain;

/** A requested resource does not exist; rendered as a {@code 404} ProblemDetail with this title and detail. */
public class ResourceNotFoundException extends RuntimeException {

    private final String title;

    public ResourceNotFoundException(String title, String detail) {
        super(detail);
        this.title = title;
    }

    public String title() {
        return title;
    }
}
