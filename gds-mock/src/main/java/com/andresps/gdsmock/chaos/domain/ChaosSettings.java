package com.andresps.gdsmock.chaos.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Chaos for one provider (spec 002, AC-15 and AC-16). Messages name the configuration property.
 *
 * @param latencyMin lower bound of the random latency, inclusive
 * @param latencyMax upper bound of the random latency, inclusive
 * @param errorRate probability of an injected {@code 500}, 0..1
 */
public record ChaosSettings(Duration latencyMin, Duration latencyMax, double errorRate) {

    /** No latency, no errors. */
    public static final ChaosSettings NONE = new ChaosSettings(Duration.ZERO, Duration.ZERO, 0.0);

    public ChaosSettings {
        Objects.requireNonNull(latencyMin, "latency-min");
        Objects.requireNonNull(latencyMax, "latency-max");
        if (latencyMin.isNegative()) {
            throw new IllegalArgumentException("latency-min must not be negative (was %s)".formatted(latencyMin));
        }
        if (latencyMax.compareTo(latencyMin) < 0) {
            throw new IllegalArgumentException(
                    "latency-max (%s) must be at least latency-min (%s)".formatted(latencyMax, latencyMin));
        }
        if (!(errorRate >= 0.0 && errorRate <= 1.0)) {
            throw new IllegalArgumentException("error-rate must be between 0 and 1 (was %s)".formatted(errorRate));
        }
    }
}
