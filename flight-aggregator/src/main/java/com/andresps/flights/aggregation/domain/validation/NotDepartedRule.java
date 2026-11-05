package com.andresps.flights.aggregation.domain.validation;

import com.andresps.flights.aggregation.domain.model.Itinerary;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The first departure must be strictly after "now". "Now" is read from the clock <b>once, when the rule is created</b>,
 * so every offer of an aggregation run is judged against the same instant and the result never depends on the order
 * (or speed) in which offers arrive. Create one validator per aggregation run.
 */
public final class NotDepartedRule implements ItineraryRule {

    private final Instant now;

    public NotDepartedRule(Clock clock) {
        this.now = Objects.requireNonNull(clock, "clock").instant();
    }

    @Override
    public Optional<DiscardReason> check(Itinerary itinerary) {
        boolean departed = !itinerary.firstDeparture().isAfter(now);
        return departed ? Optional.of(DiscardReason.ALREADY_DEPARTED) : Optional.empty();
    }
}
