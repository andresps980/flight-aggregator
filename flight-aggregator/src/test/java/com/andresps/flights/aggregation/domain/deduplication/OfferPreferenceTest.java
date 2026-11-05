package com.andresps.flights.aggregation.domain.deduplication;

import static com.andresps.flights.aggregation.domain.FlightFixtures.ALPHA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.BETA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.direct;
import static com.andresps.flights.aggregation.domain.FlightFixtures.itinerary;
import static com.andresps.flights.aggregation.domain.FlightFixtures.madBcnLhr;
import static com.andresps.flights.aggregation.domain.FlightFixtures.offer;
import static com.andresps.flights.aggregation.domain.FlightFixtures.segment;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.Itinerary;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests of the tie-break order; the aggregator tests check the same rules end to end. */
class OfferPreferenceTest {

    private final Comparator<FlightOffer> preference = OfferPreference.ORDER;

    /** Same key as {@code madBcnLhr()}, but the first segment lands 10 minutes later. */
    private static Itinerary madBcnLhrLaterFirstArrival() {
        return itinerary(
                segment("IB3456", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:25"),
                segment("IB3457", "BCN", "LHR", "2026-11-10T10:30", "2026-11-10T12:30"));
    }

    @Test
    @DisplayName("AC-4: a lower base price is preferred")
    void lowerPriceFirst() {
        assertThat(preference.compare(offer(BETA, "99.90", madBcnLhr()), offer(ALPHA, "100.00", madBcnLhr())))
                .isNegative();
    }

    @Test
    @DisplayName("AC-5: on equal price, the lower provider id is preferred")
    void providerBreaksPriceTies() {
        assertThat(preference.compare(offer(ALPHA, "99.90", madBcnLhr()), offer(BETA, "99.90", madBcnLhr())))
                .isNegative();
    }

    @Test
    @DisplayName("AC-7: on equal price and provider, earlier arrival times are preferred, segment by segment")
    void arrivalBreaksProviderTies() {
        assertThat(preference.compare(
                        offer(ALPHA, "99.90", madBcnLhr()), offer(ALPHA, "99.90", madBcnLhrLaterFirstArrival())))
                .isNegative();
    }

    @Test
    @DisplayName(
            "AC-4..8: the preference is a consistent total order (antisymmetric, transitive, 0 only for equal offers)")
    void isAConsistentTotalOrder() {
        List<FlightOffer> pool = List.of(
                offer(ALPHA, "99.90", madBcnLhr()),
                offer(BETA, "99.90", madBcnLhr()),
                offer(ALPHA, "99.904", madBcnLhrLaterFirstArrival()),
                offer(ALPHA, "100.00", madBcnLhr()),
                offer(BETA, "50.00", direct()),
                offer(ALPHA, "50.00", direct()),
                offer(ALPHA, "50.00", direct()));

        for (FlightOffer a : pool) {
            for (FlightOffer b : pool) {
                assertThat(Integer.signum(preference.compare(a, b)))
                        .isEqualTo(-Integer.signum(preference.compare(b, a)));
                assertThat(preference.compare(a, b) == 0).isEqualTo(a.equals(b));
                for (FlightOffer c : pool) {
                    if (preference.compare(a, b) <= 0 && preference.compare(b, c) <= 0) {
                        assertThat(preference.compare(a, c)).isLessThanOrEqualTo(0);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName(
            "total order: the canonical representation is the final tie-break (only decisive across different keys)")
    void canonicalFormIsTheLastTieBreak() {
        Itinerary otherFlightSameTimes =
                itinerary(segment("IB1000", "MAD", "LHR", "2026-11-10T07:00", "2026-11-10T09:30"));

        assertThat(preference.compare(offer(ALPHA, "50.00", otherFlightSameTimes), offer(ALPHA, "50.00", direct())))
                .isNegative();
        assertThat(preference.compare(offer(ALPHA, "50.00", direct()), offer(ALPHA, "50.00", direct())))
                .isZero();
    }
}
