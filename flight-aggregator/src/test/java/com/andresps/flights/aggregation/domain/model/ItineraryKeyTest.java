package com.andresps.flights.aggregation.domain.model;

import static com.andresps.flights.aggregation.domain.FlightFixtures.itinerary;
import static com.andresps.flights.aggregation.domain.FlightFixtures.madBcnLhr;
import static com.andresps.flights.aggregation.domain.FlightFixtures.segment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ItineraryKeyTest {

    @Test
    @DisplayName("AC-3: the key is a canonical representation of flight numbers, airports and departure times")
    void canonicalFormat() {
        assertThat(madBcnLhr().key().value())
                .isEqualTo("IB3456:MAD-BCN@2026-11-10T08:00:00Z|IB3457:BCN-LHR@2026-11-10T10:30:00Z");
    }

    @Test
    @DisplayName("AC-3: a key cannot be blank")
    void rejectsBlankKey() {
        assertThatThrownBy(() -> new ItineraryKey(" ")).isInstanceOf(InvalidFlightDataException.class);
        assertThatThrownBy(() -> new ItineraryKey(null)).isInstanceOf(InvalidFlightDataException.class);
    }

    @Test
    @DisplayName("AC-3: the same itinerary always yields the same key")
    void deterministic() {
        assertThat(madBcnLhr().key()).isEqualTo(madBcnLhr().key());
    }

    @Test
    @DisplayName("AC-3: arrival times are not part of the key")
    void ignoresArrivalTimes() {
        Itinerary laterArrivals = itinerary(
                segment("IB3456", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:20"),
                segment("IB3457", "BCN", "LHR", "2026-11-10T10:30", "2026-11-10T12:45"));

        assertThat(laterArrivals.key()).isEqualTo(madBcnLhr().key());
        assertThat(laterArrivals.canonicalForm()).isNotEqualTo(madBcnLhr().canonicalForm());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("identityVariations")
    @DisplayName("AC-2: changing any identity field changes the key")
    void identityFieldsChangeTheKey(Itinerary variation) {
        assertThat(variation.key()).isNotEqualTo(madBcnLhr().key());
    }

    static Stream<Arguments> identityVariations() {
        return Stream.of(
                Arguments.of(named(
                        "different flight number",
                        itinerary(
                                segment("IB3456", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                                segment("IB3999", "BCN", "LHR", "2026-11-10T10:30", "2026-11-10T12:30")))),
                Arguments.of(named(
                        "different airline",
                        itinerary(
                                segment("VY3456", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                                segment("VY3457", "BCN", "LHR", "2026-11-10T10:30", "2026-11-10T12:30")))),
                Arguments.of(named(
                        "different connection airport",
                        itinerary(
                                segment("IB3456", "MAD", "VLC", "2026-11-10T08:00", "2026-11-10T09:15"),
                                segment("IB3457", "VLC", "LHR", "2026-11-10T10:30", "2026-11-10T12:30")))),
                Arguments.of(named(
                        "different final destination",
                        itinerary(
                                segment("IB3456", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                                segment("IB3457", "BCN", "LGW", "2026-11-10T10:30", "2026-11-10T12:30")))),
                Arguments.of(named(
                        "different departure time",
                        itinerary(
                                segment("IB3456", "MAD", "BCN", "2026-11-10T08:05", "2026-11-10T09:15"),
                                segment("IB3457", "BCN", "LHR", "2026-11-10T10:30", "2026-11-10T12:30")))),
                Arguments.of(named(
                        "fewer segments",
                        itinerary(segment("IB3456", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15")))));
    }
}
