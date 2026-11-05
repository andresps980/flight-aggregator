package com.andresps.gdsmock.shared.domain;

import java.util.Objects;

/**
 * Identifier of a mock provider, e.g. {@code mock-alpha}: lower-case letters, digits and dashes.
 *
 * @param value the identifier as used in URLs and configuration
 */
public record ProviderId(String value) implements Comparable<ProviderId> {

    public ProviderId {
        Objects.requireNonNull(value, "provider id");
        if (!value.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("Provider id must be lower-case letters, digits and dashes: " + value);
        }
    }

    @Override
    public int compareTo(ProviderId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
