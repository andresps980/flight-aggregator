package com.andresps.flights.ingestion.domain;

import com.andresps.flights.aggregation.domain.model.ProviderId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One provider's outcome in a run. Offers and malformed counts come from successful partitions only (a failed attempt
 * leaves nothing behind, ADR-0015).
 *
 * @param attempts HTTP attempts over all partitions, successful or not
 * @param duration from the provider's first request to its last partition's end
 * @param circuitState breaker state at the end of the run
 */
public record ProviderReport(
        ProviderId provider,
        int partitionsRequested,
        int partitionsSucceeded,
        List<PartitionFailure> failures,
        int attempts,
        Duration duration,
        CircuitState circuitState,
        long offersReceived,
        Map<MalformedReason, Long> malformed) {

    public ProviderReport {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(circuitState, "circuitState");
        failures = List.copyOf(failures);
        malformed = MalformedCounts.nonZero(malformed);
        if (partitionsSucceeded + failures.size() != partitionsRequested) {
            throw new IllegalArgumentException("Partitions don't add up for " + provider + ": " + partitionsSucceeded
                    + " succeeded + " + failures.size() + " failed != " + partitionsRequested + " requested");
        }
    }

    public IngestionStatus status() {
        return IngestionStatus.ofPartitions(partitionsSucceeded, failures.size());
    }

    public int partitionsFailed() {
        return failures.size();
    }

    public long malformed(MalformedReason reason) {
        return malformed.getOrDefault(reason, 0L);
    }

    public long malformedTotal() {
        return MalformedCounts.total(malformed);
    }

    /** Every record parsed from successful partitions: {@code offersReceived + malformedTotal}. */
    public long recordsReceived() {
        return offersReceived + malformedTotal();
    }
}
