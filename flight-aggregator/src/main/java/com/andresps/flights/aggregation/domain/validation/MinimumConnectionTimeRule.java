package com.andresps.flights.aggregation.domain.validation;

import com.andresps.flights.aggregation.domain.model.Itinerary;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Every connection must last at least the minimum (45 minutes by default, ADR-0010); the minimum itself is valid. */
public final class MinimumConnectionTimeRule implements ItineraryRule {

    public static final Duration DEFAULT_MINIMUM = Duration.ofMinutes(45);

    private final Duration minimum;

    public MinimumConnectionTimeRule(Duration minimum) {
        if (Objects.requireNonNull(minimum, "minimum").isNegative()) {
            throw new IllegalArgumentException("minimum must not be negative, got " + minimum);
        }
        this.minimum = minimum;
    }

    @Override
    public Optional<DiscardReason> check(Itinerary itinerary) {
        boolean tooShort =
                itinerary.connectionTimes().stream().anyMatch(connection -> connection.compareTo(minimum) < 0);
        return tooShort ? Optional.of(DiscardReason.CONNECTION_TOO_SHORT) : Optional.empty();
    }
}
