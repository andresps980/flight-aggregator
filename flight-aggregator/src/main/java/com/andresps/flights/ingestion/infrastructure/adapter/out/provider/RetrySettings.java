package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

import java.time.Duration;

/**
 * Retry of one partition (ADR-0014): {@code maxAttempts} in total, waiting {@code initialBackoff × multiplier^(n-1)}
 * ± {@code jitter} between attempts. Validation messages name the property relative to the resilience settings.
 */
public record RetrySettings(int maxAttempts, Duration initialBackoff, double multiplier, double jitter) {

    public RetrySettings {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("retry.max-attempts must be at least 1, got " + maxAttempts);
        }
        ResilienceSettings.requirePositive(initialBackoff, "retry.initial-backoff");
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("retry.multiplier must be at least 1, got " + multiplier);
        }
        if (jitter < 0.0 || jitter >= 1.0) {
            throw new IllegalArgumentException("retry.jitter must be in [0, 1), got " + jitter);
        }
    }
}
