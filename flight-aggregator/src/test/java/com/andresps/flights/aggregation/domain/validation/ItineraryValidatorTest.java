package com.andresps.flights.aggregation.domain.validation;

import static com.andresps.flights.aggregation.domain.FlightFixtures.CLOCK;
import static com.andresps.flights.aggregation.domain.FlightFixtures.direct;
import static com.andresps.flights.aggregation.domain.FlightFixtures.itinerary;
import static com.andresps.flights.aggregation.domain.FlightFixtures.madBcnLhr;
import static com.andresps.flights.aggregation.domain.FlightFixtures.segment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;

import com.andresps.flights.aggregation.domain.model.Itinerary;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ItineraryValidatorTest {

    private final ItineraryValidator validator = ItineraryValidator.withDefaultRules(CLOCK);

    @ParameterizedTest(name = "{0}")
    @MethodSource("itinerariesBreakingTwoRules")
    @DisplayName(
            "AC-20: an itinerary breaking several rules is discarded once, with the first reason in evaluation order")
    void reportsFirstFailingRuleInOrder(Itinerary itinerary, DiscardReason expected) {
        assertThat(validator.validate(itinerary)).contains(expected);
    }

    static Stream<Arguments> itinerariesBreakingTwoRules() {
        return Stream.of(
                Arguments.of(
                        named(
                                "mixed airlines + arrival not after departure",
                                itinerary(
                                        segment("IB1", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T07:00"),
                                        segment("VY2", "BCN", "LHR", "2026-11-10T10:30", "2026-11-10T12:30"))),
                        DiscardReason.MIXED_AIRLINES),
                Arguments.of(
                        named(
                                "arrival not after departure + not chained",
                                itinerary(
                                        segment("IB1", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T07:00"),
                                        segment("IB2", "VLC", "LHR", "2026-11-10T10:30", "2026-11-10T12:30"))),
                        DiscardReason.ARRIVAL_NOT_AFTER_DEPARTURE),
                Arguments.of(
                        named(
                                "not chained + revisited airport + short connection",
                                itinerary(
                                        segment("IB1", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                                        segment("IB2", "VLC", "MAD", "2026-11-10T09:30", "2026-11-10T10:30"))),
                        DiscardReason.SEGMENTS_NOT_CHAINED),
                Arguments.of(
                        named(
                                "revisited airport + short connection",
                                itinerary(
                                        segment("IB1", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                                        segment("IB2", "BCN", "MAD", "2026-11-10T09:30", "2026-11-10T10:30"))),
                        DiscardReason.REVISITED_AIRPORT),
                Arguments.of(
                        named(
                                "short connection + already departed",
                                itinerary(
                                        segment("IB1", "MAD", "BCN", "2026-11-01T08:00", "2026-11-01T09:15"),
                                        segment("IB2", "BCN", "LHR", "2026-11-01T09:30", "2026-11-01T11:30"))),
                        DiscardReason.CONNECTION_TOO_SHORT),
                Arguments.of(
                        named(
                                "long layover + already departed",
                                itinerary(
                                        segment("IB1", "MAD", "BCN", "2026-11-01T08:00", "2026-11-01T09:15"),
                                        segment("IB2", "BCN", "LHR", "2026-11-02T10:30", "2026-11-02T12:30"))),
                        DiscardReason.LAYOVER_TOO_LONG),
                Arguments.of(
                        named(
                                "only already departed",
                                itinerary(segment("IB1", "MAD", "BCN", "2026-11-01T08:00", "2026-11-01T09:15"))),
                        DiscardReason.ALREADY_DEPARTED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validItineraries")
    @DisplayName("AC-21: valid direct and multi-segment itineraries in the future pass validation")
    void passesValidItineraries(Itinerary itinerary) {
        assertThat(validator.validate(itinerary)).isEmpty();
    }

    static Stream<Arguments> validItineraries() {
        return Stream.of(Arguments.of(named("direct", direct())), Arguments.of(named("two segments", madBcnLhr())));
    }

    @Test
    @DisplayName("OCP: a validator runs exactly the rules it is given, in order")
    void runsGivenRulesInOrder() {
        ItineraryRule alwaysPasses = itinerary -> Optional.empty();
        ItineraryRule alwaysLayover = itinerary -> Optional.of(DiscardReason.LAYOVER_TOO_LONG);
        ItineraryRule alwaysMixed = itinerary -> Optional.of(DiscardReason.MIXED_AIRLINES);

        var custom = new ItineraryValidator(List.of(alwaysPasses, alwaysLayover, alwaysMixed));

        assertThat(custom.validate(direct())).contains(DiscardReason.LAYOVER_TOO_LONG);
        assertThat(new ItineraryValidator(List.of()).validate(direct())).isEmpty();
    }
}
