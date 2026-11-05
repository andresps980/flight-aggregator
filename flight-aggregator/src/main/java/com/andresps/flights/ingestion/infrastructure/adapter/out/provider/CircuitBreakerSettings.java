package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

import java.time.Duration;

/**
 * Circuit breaker of one provider (ADR-0014): count-based window of {@code slidingWindow} attempts, evaluated once
 * {@code minimumCalls} were made; opens at {@code failureRateThreshold} % failures, stays open for {@code openWait},
 * then lets {@code halfOpenCalls} probes decide.
 */
public record CircuitBreakerSettings(
        int slidingWindow, int minimumCalls, float failureRateThreshold, Duration openWait, int halfOpenCalls) {

    public CircuitBreakerSettings {
        if (slidingWindow < 1) {
            throw new IllegalArgumentException(
                    "circuit-breaker.sliding-window must be at least 1, got " + slidingWindow);
        }
        if (minimumCalls < 1) {
            throw new IllegalArgumentException("circuit-breaker.minimum-calls must be at least 1, got " + minimumCalls);
        }
        if (failureRateThreshold <= 0 || failureRateThreshold > 100) {
            throw new IllegalArgumentException(
                    "circuit-breaker.failure-rate-threshold must be in (0, 100], got " + failureRateThreshold);
        }
        ResilienceSettings.requirePositive(openWait, "circuit-breaker.open-wait");
        if (halfOpenCalls < 1) {
            throw new IllegalArgumentException(
                    "circuit-breaker.half-open-calls must be at least 1, got " + halfOpenCalls);
        }
    }
}
