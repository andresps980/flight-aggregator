package com.andresps.flights.shared.domain;

import java.util.Map;

/**
 * The request conflicts with the current state; rendered as a {@code 409} ProblemDetail with this title, detail and
 * extra members (RFC 9457 extensions, e.g. the id of the conflicting resource).
 */
public class ConflictException extends RuntimeException {

    private final String title;
    private final Map<String, Object> properties;

    public ConflictException(String title, String detail, Map<String, Object> properties) {
        super(detail);
        this.title = title;
        this.properties = Map.copyOf(properties);
    }

    public String title() {
        return title;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
