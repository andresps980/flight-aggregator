package com.andresps.flights.ingestion.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A partition that failed: its day, why, and how many HTTP attempts were made (0 when short-circuited or cancelled
 * before starting).
 */
public record PartitionFailure(LocalDate day, FailureKind kind, int attempts, String detail) {

    public PartitionFailure {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(kind, "kind");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative, got " + attempts);
        }
        detail = detail == null ? "" : detail;
    }
}
