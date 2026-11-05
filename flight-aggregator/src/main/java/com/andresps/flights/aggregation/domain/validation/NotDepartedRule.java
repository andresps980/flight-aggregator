package com.andresps.flights.aggregation.domain.validation;

import com.andresps.flights.aggregation.domain.model.Itinerary;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** The first departure must be strictly after "now", as given by the injected clock. */
public final class NotDepartedRule implements ItineraryRule {

    private final Clock clock;

    public NotDepartedRule(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<DiscardReason> check(Itinerary itinerary) {
        boolean departed = !itinerary.firstDeparture().isAfter(clock.instant());
        return departed ? Optional.of(DiscardReason.ALREADY_DEPARTED) : Optional.empty();
    }
}
