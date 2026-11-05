package com.andresps.flights.aggregation.domain.model;

import static com.andresps.flights.aggregation.domain.FlightFixtures.ALPHA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.at;
import static com.andresps.flights.aggregation.domain.FlightFixtures.madBcnLhr;
import static com.andresps.flights.aggregation.domain.FlightFixtures.segment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** AC-24: invariants of segments, itineraries and offers. */
class ItineraryModelTest {

    @Nested
    class SegmentTest {

        @Test
        @DisplayName("AC-24: rejects a segment whose origin equals its destination")
        void rejectsSameOriginAndDestination() {
            assertThatThrownBy(() -> segment("IB1", "MAD", "MAD", "2026-11-10T08:00", "2026-11-10T09:00"))
                    .isInstanceOf(InvalidFlightDataException.class);
        }

        @Test
        @DisplayName("AC-24: rejects missing fields")
        void rejectsNulls() {
            FlightNumber flight = FlightNumber.parse("IB1");
            AirportCode mad = new AirportCode("MAD");
            AirportCode bcn = new AirportCode("BCN");
            var departure = at("2026-11-10T08:00");
            var arrival = at("2026-11-10T09:00");

            assertThatThrownBy(() -> new Segment(null, mad, bcn, departure, arrival))
                    .isInstanceOf(InvalidFlightDataException.class);
            assertThatThrownBy(() -> new Segment(flight, null, bcn, departure, arrival))
                    .isInstanceOf(InvalidFlightDataException.class);
            assertThatThrownBy(() -> new Segment(flight, mad, null, departure, arrival))
                    .isInstanceOf(InvalidFlightDataException.class);
            assertThatThrownBy(() -> new Segment(flight, mad, bcn, null, arrival))
                    .isInstanceOf(InvalidFlightDataException.class);
            assertThatThrownBy(() -> new Segment(flight, mad, bcn, departure, null))
                    .isInstanceOf(InvalidFlightDataException.class);
        }

        @Test
        @DisplayName("AC-14: a segment may be built with arrival before departure; a rule discards it later")
        void allowsArrivalBeforeDepartureAtConstruction() {
            Segment segment = segment("IB1", "MAD", "BCN", "2026-11-10T09:00", "2026-11-10T08:00");

            assertThat(segment.airline()).isEqualTo(new AirlineCode("IB"));
        }
    }

    @Nested
    class ItineraryTest {

        @Test
        @DisplayName("AC-24: an itinerary needs at least one segment")
        void rejectsEmpty() {
            assertThatThrownBy(() -> new Itinerary(List.of())).isInstanceOf(InvalidFlightDataException.class);
            assertThatThrownBy(() -> new Itinerary(null)).isInstanceOf(InvalidFlightDataException.class);
        }

        @Test
        @DisplayName("AC-24: an itinerary rejects null segments")
        void rejectsNullSegment() {
            List<Segment> segments = Arrays.asList(madBcnLhr().segments().getFirst(), null);

            assertThatThrownBy(() -> new Itinerary(segments)).isInstanceOf(InvalidFlightDataException.class);
        }

        @Test
        @DisplayName("an itinerary is immutable: later changes to the source list do not leak in")
        void defensiveCopy() {
            List<Segment> source = new ArrayList<>(madBcnLhr().segments());
            Itinerary itinerary = new Itinerary(source);

            source.removeLast();

            assertThat(itinerary.segments()).hasSize(2);
            assertThat(itinerary.firstDeparture()).isEqualTo(at("2026-11-10T08:00"));
        }
    }

    @Nested
    class FlightOfferTest {

        @ParameterizedTest
        @ValueSource(strings = {"0", "0.00", "0.004"})
        @DisplayName("AC-24: an offer's base price must be greater than zero")
        void rejectsNonPositivePrice(String price) {
            Money basePrice = Money.eur(price);

            assertThatThrownBy(() -> new FlightOffer(madBcnLhr(), ALPHA, basePrice))
                    .isInstanceOf(InvalidFlightDataException.class);
        }

        @Test
        @DisplayName("AC-24: an offer rejects missing fields")
        void rejectsNulls() {
            Money price = Money.eur("10.00");

            assertThatThrownBy(() -> new FlightOffer(null, ALPHA, price))
                    .isInstanceOf(InvalidFlightDataException.class);
            assertThatThrownBy(() -> new FlightOffer(madBcnLhr(), null, price))
                    .isInstanceOf(InvalidFlightDataException.class);
            assertThatThrownBy(() -> new FlightOffer(madBcnLhr(), ALPHA, null))
                    .isInstanceOf(InvalidFlightDataException.class);
        }
    }
}
