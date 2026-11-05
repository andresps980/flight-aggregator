package com.andresps.flights.aggregation.domain.validation;

import static com.andresps.flights.aggregation.domain.FlightFixtures.CLOCK;
import static com.andresps.flights.aggregation.domain.FlightFixtures.NOW;
import static com.andresps.flights.aggregation.domain.FlightFixtures.direct;
import static com.andresps.flights.aggregation.domain.FlightFixtures.itinerary;
import static com.andresps.flights.aggregation.domain.FlightFixtures.segment;
import static com.andresps.flights.aggregation.domain.FlightFixtures.twoLegsWithConnection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.aggregation.domain.model.Itinerary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Rules on connection times and departure time (AC-17 – AC-19). */
class TimingRulesTest {

    /** A real-looking clock whose time moves forward on demand. */
    private static final class AdvancingClock extends Clock {
        private Instant now;

        AdvancingClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Nested
    class MinimumConnectionTimeRuleTest {

        private final ItineraryRule rule = new MinimumConnectionTimeRule(MinimumConnectionTimeRule.DEFAULT_MINIMUM);

        @Test
        @DisplayName("a negative minimum is a configuration error")
        void rejectsNegativeMinimum() {
            assertThatThrownBy(() -> new MinimumConnectionTimeRule(Duration.ofMinutes(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("AC-17: the default minimum connection time is 45 minutes")
        void defaultIs45Minutes() {
            assertThat(MinimumConnectionTimeRule.DEFAULT_MINIMUM).isEqualTo(Duration.ofMinutes(45));
        }

        @ParameterizedTest(name = "connection of {0}")
        @ValueSource(strings = {"PT44M59S", "PT44M", "PT1M", "PT0S", "PT-30M"})
        @DisplayName(
                "AC-17: a connection shorter than 45 minutes (including negative) is discarded with CONNECTION_TOO_SHORT")
        void discardsShortConnections(String connection) {
            Itinerary itinerary = twoLegsWithConnection(Duration.parse(connection));

            assertThat(rule.check(itinerary)).contains(DiscardReason.CONNECTION_TOO_SHORT);
        }

        @ParameterizedTest(name = "connection of {0} min")
        @ValueSource(longs = {45, 46, 180})
        @DisplayName("AC-17: a connection of 45 minutes or more passes")
        void passesLongEnoughConnections(long minutes) {
            assertThat(rule.check(twoLegsWithConnection(Duration.ofMinutes(minutes))))
                    .isEmpty();
        }

        @Test
        @DisplayName("AC-17: every connection is checked, not only the first")
        void checksEveryConnection() {
            Itinerary itinerary = itinerary(
                    segment("IB1", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                    segment("IB2", "BCN", "PMI", "2026-11-10T10:30", "2026-11-10T11:30"),
                    segment("IB3", "PMI", "LHR", "2026-11-10T12:00", "2026-11-10T14:30"));

            assertThat(rule.check(itinerary)).contains(DiscardReason.CONNECTION_TOO_SHORT);
        }

        @Test
        @DisplayName("AC-21: a direct flight has no connection and passes")
        void passesDirect() {
            assertThat(rule.check(direct())).isEmpty();
        }
    }

    @Nested
    class MaximumLayoverRuleTest {

        private final ItineraryRule rule = new MaximumLayoverRule(MaximumLayoverRule.DEFAULT_MAXIMUM);

        @Test
        @DisplayName("a negative maximum is a configuration error")
        void rejectsNegativeMaximum() {
            assertThatThrownBy(() -> new MaximumLayoverRule(Duration.ofMinutes(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("AC-18: the default maximum layover is 24 hours")
        void defaultIs24Hours() {
            assertThat(MaximumLayoverRule.DEFAULT_MAXIMUM).isEqualTo(Duration.ofHours(24));
        }

        @ParameterizedTest(name = "layover of {0}")
        @ValueSource(strings = {"PT24H0M1S", "PT24H1M", "PT48H"})
        @DisplayName("AC-18: a layover longer than 24 hours is discarded with LAYOVER_TOO_LONG")
        void discardsLongLayovers(String layover) {
            Itinerary itinerary = twoLegsWithConnection(Duration.parse(layover));

            assertThat(rule.check(itinerary)).contains(DiscardReason.LAYOVER_TOO_LONG);
        }

        @ParameterizedTest(name = "layover of {0} min")
        @ValueSource(longs = {24 * 60, 24 * 60 - 1, 75})
        @DisplayName("AC-18: a layover of 24 hours or less passes")
        void passesAcceptableLayovers(long minutes) {
            assertThat(rule.check(twoLegsWithConnection(Duration.ofMinutes(minutes))))
                    .isEmpty();
        }

        @Test
        @DisplayName("AC-21: a direct flight has no layover and passes")
        void passesDirect() {
            assertThat(rule.check(direct())).isEmpty();
        }
    }

    @Nested
    class NotDepartedRuleTest {

        private final ItineraryRule rule = new NotDepartedRule(CLOCK);

        @ParameterizedTest(name = "first departure {0} s from now")
        @ValueSource(longs = {0, -1, -3600})
        @DisplayName("AC-19: an itinerary whose first departure is not after now is discarded with ALREADY_DEPARTED")
        void discardsDeparted(long secondsFromNow) {
            var departure = NOW.plusSeconds(secondsFromNow);
            Itinerary itinerary =
                    itinerary(segment("IB1", "MAD", "BCN", departure, departure.plus(Duration.ofHours(1))));

            assertThat(rule.check(itinerary)).contains(DiscardReason.ALREADY_DEPARTED);
        }

        @Test
        @DisplayName(
                "AC-19: now is read once, when the rule is created, so a run's result never depends on offer order")
        void readsNowOnce() {
            var clock = new AdvancingClock(NOW);
            ItineraryRule snapshotRule = new NotDepartedRule(clock);
            var departure = NOW.plusSeconds(60);
            Itinerary departsInOneMinute =
                    itinerary(segment("IB1", "MAD", "BCN", departure, departure.plus(Duration.ofHours(1))));

            clock.advance(Duration.ofHours(1));

            assertThat(snapshotRule.check(departsInOneMinute)).isEmpty();
            assertThat(new NotDepartedRule(clock).check(departsInOneMinute)).contains(DiscardReason.ALREADY_DEPARTED);
        }

        @ParameterizedTest(name = "first departure {0} s from now")
        @ValueSource(longs = {1, 3600})
        @DisplayName("AC-19: an itinerary departing after now passes")
        void passesFutureDepartures(long secondsFromNow) {
            var departure = NOW.plusSeconds(secondsFromNow);
            Itinerary itinerary =
                    itinerary(segment("IB1", "MAD", "BCN", departure, departure.plus(Duration.ofHours(1))));

            assertThat(rule.check(itinerary)).isEmpty();
        }
    }
}
