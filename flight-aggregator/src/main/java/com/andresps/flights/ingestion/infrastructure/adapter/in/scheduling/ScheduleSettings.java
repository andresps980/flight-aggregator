package com.andresps.flights.ingestion.infrastructure.adapter.in.scheduling;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code aggregator.ingestion.schedule.*} (spec 004, AC-20): a first run {@code initial-delay} after the application
 * is ready, then {@code fixed-delay} after each scheduled run ends (ADR-0006).
 */
public record ScheduleSettings(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10s") Duration initialDelay,
        @DefaultValue("15m") Duration fixedDelay) {

    private static final String PREFIX = "aggregator.ingestion.schedule.";

    public ScheduleSettings {
        Objects.requireNonNull(initialDelay, PREFIX + "initial-delay");
        Objects.requireNonNull(fixedDelay, PREFIX + "fixed-delay");
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException(PREFIX + "initial-delay must not be negative, got " + initialDelay);
        }
        if (fixedDelay.isNegative() || fixedDelay.isZero()) {
            throw new IllegalArgumentException(PREFIX + "fixed-delay must be positive, got " + fixedDelay);
        }
    }
}
