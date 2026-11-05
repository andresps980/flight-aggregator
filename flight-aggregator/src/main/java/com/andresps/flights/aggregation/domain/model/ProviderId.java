package com.andresps.flights.aggregation.domain.model;

/** Identifier of the GDS provider an offer comes from (e.g. {@code mock-alpha}). Ordered lexicographically. */
public record ProviderId(String value) implements Comparable<ProviderId> {

    public ProviderId {
        value = InvalidFlightDataException.requireField(value, "Provider id").strip();
        if (value.isEmpty()) {
            throw new InvalidFlightDataException("Provider id must not be blank");
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
