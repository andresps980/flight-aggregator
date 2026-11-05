package com.andresps.flights.aggregation.domain.validation;

import static com.andresps.flights.aggregation.domain.FlightFixtures.direct;
import static com.andresps.flights.aggregation.domain.FlightFixtures.itinerary;
import static com.andresps.flights.aggregation.domain.FlightFixtures.madBcnLhr;
import static com.andresps.flights.aggregation.domain.FlightFixtures.segment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;

import com.andresps.flights.aggregation.domain.model.Itinerary;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Rules that look only at the shape of an itinerary (AC-13 – AC-16). */
class StructuralRulesTest {

    static Stream<Arguments> validItineraries() {
        return Stream.of(
                Arguments.of(named("direct", direct())),
                Arguments.of(named("two segments", madBcnLhr())),
                Arguments.of(named(
                        "three segments",
                        itinerary(
                                segment("IB1", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                                segment("IB2", "BCN", "PMI", "2026-11-10T10:30", "2026-11-10T11:30"),
                                segment("IB3", "PMI", "LHR", "2026-11-10T13:00", "2026-11-10T15:30")))));
    }

    @Nested
    class SingleAirlineRuleTest {

        private final ItineraryRule rule = new SingleAirlineRule();

        @ParameterizedTest(name = "{0}")
        @MethodSource("mixedAirlines")
        @DisplayName("AC-13: segments operated by more than one airline are discarded with MIXED_AIRLINES")
        void discardsMixedAirlines(Itinerary itinerary) {
            assertThat(rule.check(itinerary)).contains(DiscardReason.MIXED_AIRLINES);
        }

        static Stream<Arguments> mixedAirlines() {
            return Stream.of(
                    Arguments.of(named(
                            "second segment on another airline",
                            itinerary(
                                    segment("IB3456", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                                    segment("VY1234", "BCN", "LHR", "2026-11-10T10:30", "2026-11-10T12:30")))),
                    Arguments.of(named(
                            "only the last of three differs",
                            itinerary(
                                    segment("IB1", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                                    segment("IB2", "BCN", "PMI", "2026-11-10T10:30", "2026-11-10T11:30"),
                                    segment("U23", "PMI", "LHR", "2026-11-10T13:00", "2026-11-10T15:30")))));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.andresps.flights.aggregation.domain.validation.StructuralRulesTest#validItineraries")
        @DisplayName("AC-21: a single-airline itinerary passes")
        void passesSingleAirline(Itinerary itinerary) {
            assertThat(rule.check(itinerary)).isEmpty();
        }
    }

    @Nested
    class ArrivalAfterDepartureRuleTest {

        private final ItineraryRule rule = new ArrivalAfterDepartureRule();

        @ParameterizedTest(name = "{0}")
        @MethodSource("arrivalNotAfterDeparture")
        @DisplayName(
                "AC-14: a segment arriving at or before its departure is discarded with ARRIVAL_NOT_AFTER_DEPARTURE")
        void discardsArrivalNotAfterDeparture(Itinerary itinerary) {
            assertThat(rule.check(itinerary)).contains(DiscardReason.ARRIVAL_NOT_AFTER_DEPARTURE);
        }

        static Stream<Arguments> arrivalNotAfterDeparture() {
            return Stream.of(
                    Arguments.of(named(
                            "arrival equals departure",
                            itinerary(segment("IB1", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T08:00")))),
                    Arguments.of(named(
                            "arrival before departure",
                            itinerary(segment("IB1", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T07:59")))),
                    Arguments.of(named(
                            "only the second segment is wrong",
                            itinerary(
                                    segment("IB1", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                                    segment("IB2", "BCN", "LHR", "2026-11-10T10:30", "2026-11-10T10:00")))));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.andresps.flights.aggregation.domain.validation.StructuralRulesTest#validItineraries")
        @DisplayName("AC-21: segments that arrive after they depart pass")
        void passesOrderedTimes(Itinerary itinerary) {
            assertThat(rule.check(itinerary)).isEmpty();
        }
    }

    @Nested
    class ChainedSegmentsRuleTest {

        private final ItineraryRule rule = new ChainedSegmentsRule();

        @ParameterizedTest(name = "{0}")
        @MethodSource("unchained")
        @DisplayName(
                "AC-15: a segment not departing from the previous destination is discarded with SEGMENTS_NOT_CHAINED")
        void discardsUnchained(Itinerary itinerary) {
            assertThat(rule.check(itinerary)).contains(DiscardReason.SEGMENTS_NOT_CHAINED);
        }

        static Stream<Arguments> unchained() {
            return Stream.of(
                    Arguments.of(named(
                            "MAD-BCN then MAD-LHR",
                            itinerary(
                                    segment("IB1", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                                    segment("IB2", "MAD", "LHR", "2026-11-10T10:30", "2026-11-10T12:30")))),
                    Arguments.of(named(
                            "gap in the third segment",
                            itinerary(
                                    segment("IB1", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                                    segment("IB2", "BCN", "PMI", "2026-11-10T10:30", "2026-11-10T11:30"),
                                    segment("IB3", "VLC", "LHR", "2026-11-10T13:00", "2026-11-10T15:30")))));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.andresps.flights.aggregation.domain.validation.StructuralRulesTest#validItineraries")
        @DisplayName("AC-21: chained segments pass")
        void passesChained(Itinerary itinerary) {
            assertThat(rule.check(itinerary)).isEmpty();
        }
    }

    @Nested
    class NoRevisitedAirportRuleTest {

        private final ItineraryRule rule = new NoRevisitedAirportRule();

        @ParameterizedTest(name = "{0}")
        @MethodSource("revisits")
        @DisplayName("AC-16: an itinerary visiting the same airport twice is discarded with REVISITED_AIRPORT")
        void discardsRevisits(Itinerary itinerary) {
            assertThat(rule.check(itinerary)).contains(DiscardReason.REVISITED_AIRPORT);
        }

        static Stream<Arguments> revisits() {
            return Stream.of(
                    Arguments.of(named(
                            "MAD-BCN-MAD-LHR",
                            itinerary(
                                    segment("IB1", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                                    segment("IB2", "BCN", "MAD", "2026-11-10T10:30", "2026-11-10T11:45"),
                                    segment("IB3", "MAD", "LHR", "2026-11-10T13:00", "2026-11-10T15:30")))),
                    Arguments.of(named(
                            "back to the origin MAD-BCN-MAD",
                            itinerary(
                                    segment("IB1", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                                    segment("IB2", "BCN", "MAD", "2026-11-10T10:30", "2026-11-10T11:45")))),
                    Arguments.of(named(
                            "final destination already visited MAD-BCN-LHR-BCN",
                            itinerary(
                                    segment("IB1", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                                    segment("IB2", "BCN", "LHR", "2026-11-10T10:30", "2026-11-10T12:30"),
                                    segment("IB3", "LHR", "BCN", "2026-11-10T14:00", "2026-11-10T16:15")))));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.andresps.flights.aggregation.domain.validation.StructuralRulesTest#validItineraries")
        @DisplayName("AC-21: itineraries visiting each airport once pass")
        void passesDistinctAirports(Itinerary itinerary) {
            assertThat(rule.check(itinerary)).isEmpty();
        }
    }
}
