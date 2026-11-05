package com.andresps.flights.aggregation.domain.validation;

import com.andresps.flights.aggregation.domain.model.Itinerary;
import java.util.Optional;

/**
 * One business rule that an itinerary must satisfy to be sold.
 *
 * <p>Contract (so rules are interchangeable): returns {@link Optional#empty()} when the itinerary satisfies the rule,
 * or the single {@link DiscardReason} it violates. Never throws for a well-formed {@link Itinerary}, has no side
 * effects, and checks only its own concern, so rules can be composed in any order by {@link ItineraryValidator}.
 */
@FunctionalInterface
public interface ItineraryRule {

    Optional<DiscardReason> check(Itinerary itinerary);
}
