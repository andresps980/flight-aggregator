package com.andresps.flights.aggregation.domain.validation;

import com.andresps.flights.aggregation.domain.model.Itinerary;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** No layover may exceed the maximum (24 hours by default, ADR-0010); the maximum itself is valid. */
public final class MaximumLayoverRule implements ItineraryRule {

    public static final Duration DEFAULT_MAXIMUM = Duration.ofHours(24);

    private final Duration maximum;

    public MaximumLayoverRule(Duration maximum) {
        this.maximum = Objects.requireNonNull(maximum, "maximum");
    }

    @Override
    public Optional<DiscardReason> check(Itinerary itinerary) {
        boolean tooLong = itinerary.connectionTimes().stream().anyMatch(layover -> layover.compareTo(maximum) > 0);
        return tooLong ? Optional.of(DiscardReason.LAYOVER_TOO_LONG) : Optional.empty();
    }
}
