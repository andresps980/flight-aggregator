package com.andresps.gdsmock.inventory.domain.model;

import java.util.Objects;

/**
 * The malformation of a record.
 *
 * @param segmentIndex the affected segment (the corrupted origin code or the unparseable departure time)
 */
public record Malformation(MalformationKind kind, int segmentIndex) {

    public Malformation {
        Objects.requireNonNull(kind, "kind");
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must be >= 0");
        }
    }
}
