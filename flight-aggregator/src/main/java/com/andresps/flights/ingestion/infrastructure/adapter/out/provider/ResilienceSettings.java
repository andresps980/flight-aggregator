package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

import java.time.Duration;
import java.util.Objects;

/**
 * Resilience of one provider feed (ADR-0014). Defaults are justified against the mock's 100–5000 ms latency and 10 %
 * error rate in spec 003.
 *
 * @param connectTimeout time to open the TCP connection
 * @param readTimeout max wait for the first byte and between body bytes
 * @param attemptTimeout total deadline of one attempt (request + whole body), so a trickling body can't hang a run
 * @param maxRecordsPerPartition records one attempt may buffer, so a runaway feed can't exhaust the heap (ADR-0015)
 */
public record ResilienceSettings(
        Duration connectTimeout,
        Duration readTimeout,
        Duration attemptTimeout,
        RetrySettings retry,
        CircuitBreakerSettings circuitBreaker,
        long maxRecordsPerPartition) {

    /** About 5× the largest day partition of one provider at 1,000,000 itineraries (≈ 90,000 records). */
    public static final long DEFAULT_MAX_RECORDS_PER_PARTITION = 500_000;

    public ResilienceSettings {
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(readTimeout, "read-timeout");
        requirePositive(attemptTimeout, "attempt-timeout");
        if (attemptTimeout.compareTo(readTimeout) <= 0) {
            throw new IllegalArgumentException(
                    "attempt-timeout (" + attemptTimeout + ") must be greater than read-timeout (" + readTimeout + ")");
        }
        Objects.requireNonNull(retry, "retry");
        Objects.requireNonNull(circuitBreaker, "circuit-breaker");
        if (maxRecordsPerPartition < 1) {
            throw new IllegalArgumentException(
                    "max-records-per-partition must be at least 1, got " + maxRecordsPerPartition);
        }
    }

    /** With the default record limit. */
    public ResilienceSettings(
            Duration connectTimeout,
            Duration readTimeout,
            Duration attemptTimeout,
            RetrySettings retry,
            CircuitBreakerSettings circuitBreaker) {
        this(connectTimeout, readTimeout, attemptTimeout, retry, circuitBreaker, DEFAULT_MAX_RECORDS_PER_PARTITION);
    }

    public static ResilienceSettings defaults() {
        return new ResilienceSettings(
                Duration.ofSeconds(1),
                Duration.ofSeconds(6),
                Duration.ofSeconds(60),
                new RetrySettings(3, Duration.ofMillis(500), 2.0, 0.5),
                new CircuitBreakerSettings(10, 10, 80f, Duration.ofSeconds(30), 2));
    }

    static void requirePositive(Duration value, String property) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(property + " must be a positive duration, got " + value);
        }
    }
}
