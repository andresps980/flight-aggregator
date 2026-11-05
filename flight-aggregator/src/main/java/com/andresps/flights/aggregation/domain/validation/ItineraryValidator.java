package com.andresps.flights.aggregation.domain.validation;

import com.andresps.flights.aggregation.domain.model.Itinerary;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Runs rules in order and reports the first violation, so each invalid itinerary is counted under exactly one reason.
 * New rules are added by composing a different list, without changing this class.
 */
public final class ItineraryValidator {

    private final List<ItineraryRule> rules;

    public ItineraryValidator(List<ItineraryRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * The rules of spec 001 in their evaluation order: structural, then timing, then time-relative (AC-20). "Now" is
     * taken from the clock at this call, so build one validator per aggregation run.
     */
    public static ItineraryValidator withDefaultRules(Clock clock) {
        return new ItineraryValidator(List.of(
                new SingleAirlineRule(),
                new ArrivalAfterDepartureRule(),
                new ChainedSegmentsRule(),
                new NoRevisitedAirportRule(),
                new MinimumConnectionTimeRule(MinimumConnectionTimeRule.DEFAULT_MINIMUM),
                new MaximumLayoverRule(MaximumLayoverRule.DEFAULT_MAXIMUM),
                new NotDepartedRule(clock)));
    }

    /** The first violated rule's reason, or empty if the itinerary can be sold. */
    public Optional<DiscardReason> validate(Itinerary itinerary) {
        for (ItineraryRule rule : rules) {
            Optional<DiscardReason> reason = rule.check(itinerary);
            if (reason.isPresent()) {
                return reason;
            }
        }
        return Optional.empty();
    }
}
