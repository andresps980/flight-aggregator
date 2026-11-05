package com.andresps.flights.ingestion.application.service;

import java.time.Duration;
import java.util.Objects;

/**
 * Run-level settings of the ingestion use case. Validation messages start with the property name relative to
 * {@code aggregator.ingestion}, so configuration can report the full property.
 *
 * @param horizonDays days after the reference date to ingest (1..30)
 * @param maxConcurrentDays days aggregated at the same time (bounds memory, ADR-0016)
 * @param maxConcurrentPartitions partitions in flight per provider (bounds provider load)
 * @param runTimeout deadline of a whole run; unfinished partitions fail as {@code RUN_TIMEOUT}
 */
public record IngestionSettings(
        int horizonDays, int maxConcurrentDays, int maxConcurrentPartitions, Duration runTimeout) {

    public static final int MAX_HORIZON_DAYS = 30;

    public IngestionSettings {
        if (horizonDays < 1 || horizonDays > MAX_HORIZON_DAYS) {
            throw new IllegalArgumentException(
                    "horizon-days must be between 1 and " + MAX_HORIZON_DAYS + ", got " + horizonDays);
        }
        if (maxConcurrentDays < 1) {
            throw new IllegalArgumentException("max-concurrent-days must be at least 1, got " + maxConcurrentDays);
        }
        if (maxConcurrentPartitions < 1) {
            throw new IllegalArgumentException(
                    "max-concurrent-partitions must be at least 1, got " + maxConcurrentPartitions);
        }
        Objects.requireNonNull(runTimeout, "run-timeout");
        if (runTimeout.isNegative() || runTimeout.isZero()) {
            throw new IllegalArgumentException("run-timeout must be positive, got " + runTimeout);
        }
    }
}
