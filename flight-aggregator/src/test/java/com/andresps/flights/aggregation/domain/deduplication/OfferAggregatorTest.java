package com.andresps.flights.aggregation.domain.deduplication;

import static com.andresps.flights.aggregation.domain.FlightFixtures.ALPHA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.BETA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.CLOCK;
import static com.andresps.flights.aggregation.domain.FlightFixtures.GAMMA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.direct;
import static com.andresps.flights.aggregation.domain.FlightFixtures.itinerary;
import static com.andresps.flights.aggregation.domain.FlightFixtures.madBcnLhr;
import static com.andresps.flights.aggregation.domain.FlightFixtures.offer;
import static com.andresps.flights.aggregation.domain.FlightFixtures.segment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Named.named;

import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.Itinerary;
import com.andresps.flights.aggregation.domain.model.Money;
import com.andresps.flights.aggregation.domain.pricing.PercentageMarkupPolicy;
import com.andresps.flights.aggregation.domain.validation.DiscardReason;
import com.andresps.flights.aggregation.domain.validation.ItineraryValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OfferAggregatorTest {

    private static AggregationResult aggregate(List<FlightOffer> offers) {
        var aggregator =
                new OfferAggregator(ItineraryValidator.withDefaultRules(CLOCK), PercentageMarkupPolicy.defaultPolicy());
        offers.forEach(aggregator::accept);
        return aggregator.result();
    }

    private static AggregatedItinerary single(AggregationResult result) {
        assertThat(result.itineraries()).hasSize(1);
        return result.itineraries().getFirst();
    }

    /** Every ordering of the given offers, to prove results never depend on acceptance order. */
    private static List<List<FlightOffer>> permutations(List<FlightOffer> offers) {
        if (offers.size() <= 1) {
            return List.of(offers);
        }
        List<List<FlightOffer>> result = new ArrayList<>();
        for (FlightOffer head : offers) {
            List<FlightOffer> rest = new ArrayList<>(offers);
            rest.remove(head);
            for (List<FlightOffer> tail : permutations(rest)) {
                List<FlightOffer> permutation = new ArrayList<>();
                permutation.add(head);
                permutation.addAll(tail);
                result.add(permutation);
            }
        }
        return result;
    }

    @Nested
    class Identity {

        @Test
        @DisplayName("AC-1: identical itineraries from different providers become one itinerary listing both providers")
        void mergesAcrossProviders() {
            AggregationResult result =
                    aggregate(List.of(offer(ALPHA, "100.00", madBcnLhr()), offer(BETA, "110.00", madBcnLhr())));

            AggregatedItinerary aggregated = single(result);
            assertThat(aggregated.key()).isEqualTo(madBcnLhr().key());
            assertThat(aggregated.providers()).containsExactly(ALPHA, BETA);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("differentItineraries")
        @DisplayName("AC-2: offers differing in an identity field produce separate itineraries")
        void keepsDifferentItinerariesApart(Itinerary other) {
            AggregationResult result =
                    aggregate(List.of(offer(ALPHA, "100.00", madBcnLhr()), offer(BETA, "100.00", other)));

            assertThat(result.itineraries()).hasSize(2);
        }

        static Stream<Arguments> differentItineraries() {
            return Stream.of(
                    Arguments.of(named(
                            "different flight number",
                            itinerary(
                                    segment("IB3456", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                                    segment("IB3999", "BCN", "LHR", "2026-11-10T10:30", "2026-11-10T12:30")))),
                    Arguments.of(named(
                            "different connection airport",
                            itinerary(
                                    segment("IB3456", "MAD", "VLC", "2026-11-10T08:00", "2026-11-10T09:15"),
                                    segment("IB3457", "VLC", "LHR", "2026-11-10T10:30", "2026-11-10T12:30")))),
                    Arguments.of(named(
                            "different departure time",
                            itinerary(
                                    segment("IB3456", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                                    segment("IB3457", "BCN", "LHR", "2026-11-10T10:35", "2026-11-10T12:30")))));
        }
    }

    @Nested
    class Selection {

        @Test
        @DisplayName("AC-4: the cheapest base price is selected, whatever the acceptance order")
        void cheapestWinsInAnyOrder() {
            List<FlightOffer> offers = List.of(
                    offer(ALPHA, "120.00", madBcnLhr()),
                    offer(BETA, "99.90", madBcnLhr()),
                    offer(GAMMA, "105.00", madBcnLhr()));

            for (List<FlightOffer> ordering : permutations(offers)) {
                AggregatedItinerary aggregated = single(aggregate(ordering));

                assertThat(aggregated.selectedOffer().provider())
                        .as("order %s", ordering)
                        .isEqualTo(BETA);
                assertThat(aggregated.basePrice()).isEqualTo(Money.eur("99.90"));
            }
        }

        @Test
        @DisplayName("AC-5: on equal base price, the lowest provider id wins, whatever the acceptance order")
        void providerIdBreaksTies() {
            List<FlightOffer> offers = List.of(offer(BETA, "99.90", madBcnLhr()), offer(ALPHA, "99.90", madBcnLhr()));

            for (List<FlightOffer> ordering : permutations(offers)) {
                assertThat(single(aggregate(ordering)).selectedOffer().provider())
                        .isEqualTo(ALPHA);
            }
        }

        @Test
        @DisplayName("AC-6: duplicates from the same provider are merged, cheapest selected, provider listed once")
        void mergesSameProviderDuplicates() {
            AggregationResult result =
                    aggregate(List.of(offer(GAMMA, "80.00", madBcnLhr()), offer(GAMMA, "75.00", madBcnLhr())));

            AggregatedItinerary aggregated = single(result);
            assertThat(aggregated.basePrice()).isEqualTo(Money.eur("75.00"));
            assertThat(aggregated.providers()).containsExactly(GAMMA);
            assertThat(result.statistics().duplicatesMerged()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-7: on equal price and provider, the earliest arrival wins, whatever the acceptance order")
        void earliestArrivalBreaksSameProviderTies() {
            Itinerary laterArrival = itinerary(
                    segment("IB3456", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                    segment("IB3457", "BCN", "LHR", "2026-11-10T10:30", "2026-11-10T12:50"));
            List<FlightOffer> offers = List.of(offer(ALPHA, "99.90", laterArrival), offer(ALPHA, "99.90", madBcnLhr()));

            for (List<FlightOffer> ordering : permutations(offers)) {
                assertThat(single(aggregate(ordering)).itinerary()).isEqualTo(madBcnLhr());
            }
        }

        @Test
        @DisplayName("AC-8: prices differing only beyond the 2nd decimal tie and fall to the provider tie-break")
        void subCentDifferencesTie() {
            List<FlightOffer> offers = List.of(offer(BETA, "99.896", madBcnLhr()), offer(ALPHA, "99.904", madBcnLhr()));

            for (List<FlightOffer> ordering : permutations(offers)) {
                AggregatedItinerary aggregated = single(aggregate(ordering));

                assertThat(aggregated.selectedOffer().provider()).isEqualTo(ALPHA);
                assertThat(aggregated.basePrice()).isEqualTo(Money.eur("99.90"));
            }
        }
    }

    @Nested
    class Pricing {

        @Test
        @DisplayName("AC-9: the aggregated itinerary exposes the selected base price and the final price with markup")
        void exposesBaseAndFinalPrice() {
            AggregatedItinerary aggregated = single(aggregate(List.of(offer(ALPHA, "100.00", madBcnLhr()))));

            assertThat(aggregated.basePrice()).isEqualTo(Money.eur("100.00"));
            assertThat(aggregated.finalPrice()).isEqualTo(Money.eur("105.00"));
        }

        @Test
        @DisplayName("AC-9: the markup is applied to the selected (cheapest) base price")
        void marksUpTheSelectedPrice() {
            AggregatedItinerary aggregated =
                    single(aggregate(List.of(offer(ALPHA, "200.00", madBcnLhr()), offer(BETA, "10.10", madBcnLhr()))));

            assertThat(aggregated.finalPrice()).isEqualTo(Money.eur("10.60"));
        }
    }

    @Nested
    class Validation {

        @Test
        @DisplayName("AC-13..19: an invalid offer is discarded, counted under its reason and not aggregated")
        void discardsInvalidOffers() {
            Itinerary mixed = itinerary(
                    segment("IB3456", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                    segment("VY1234", "BCN", "LHR", "2026-11-10T10:30", "2026-11-10T12:30"));

            AggregationResult result = aggregate(List.of(offer(ALPHA, "50.00", mixed)));

            assertThat(result.itineraries()).isEmpty();
            assertThat(result.statistics().discarded(DiscardReason.MIXED_AIRLINES))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("AC-13..19: a cheaper invalid offer with the same key never takes part in deduplication")
        void invalidOffersDoNotCompete() {
            Itinerary sameKeyButArrivesBeforeDeparture = itinerary(
                    segment("IB3456", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T07:00"),
                    segment("IB3457", "BCN", "LHR", "2026-11-10T10:30", "2026-11-10T12:30"));

            AggregationResult result = aggregate(List.of(
                    offer(ALPHA, "1.00", sameKeyButArrivesBeforeDeparture), offer(BETA, "100.00", madBcnLhr())));

            AggregatedItinerary aggregated = single(result);
            assertThat(aggregated.selectedOffer().provider()).isEqualTo(BETA);
            assertThat(aggregated.providers()).containsExactly(BETA);
            assertThat(result.statistics().discarded(DiscardReason.ARRIVAL_NOT_AFTER_DEPARTURE))
                    .isEqualTo(1);
            assertThat(result.statistics().duplicatesMerged()).isZero();
        }
    }

    @Nested
    class Statistics {

        @Test
        @DisplayName("AC-22: statistics report received per provider, discarded per reason, duplicates and itineraries")
        void reportsStatistics() {
            Itinerary departed = itinerary(segment("IB9", "MAD", "BCN", "2026-11-01T08:00", "2026-11-01T09:15"));
            Itinerary mixed = itinerary(
                    segment("IB3456", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                    segment("VY1234", "BCN", "LHR", "2026-11-10T10:30", "2026-11-10T12:30"));

            AggregationResult result = aggregate(List.of(
                    offer(ALPHA, "100.00", madBcnLhr()),
                    offer(ALPHA, "80.00", direct()),
                    offer(ALPHA, "30.00", departed),
                    offer(BETA, "95.00", madBcnLhr()),
                    offer(BETA, "60.00", mixed),
                    offer(GAMMA, "97.00", madBcnLhr()),
                    offer(GAMMA, "82.00", direct())));

            AggregationStatistics stats = result.statistics();
            assertThat(stats.offersReceivedByProvider())
                    .containsOnly(entry(ALPHA, 3L), entry(BETA, 2L), entry(GAMMA, 2L));
            assertThat(stats.discardedByReason())
                    .containsOnly(entry(DiscardReason.ALREADY_DEPARTED, 1L), entry(DiscardReason.MIXED_AIRLINES, 1L));
            assertThat(stats.duplicatesMerged()).isEqualTo(3);
            assertThat(stats.itinerariesProduced())
                    .isEqualTo(2)
                    .isEqualTo(result.itineraries().size());
            assertThat(stats.totalReceived())
                    .isEqualTo(7)
                    .isEqualTo(stats.totalDiscarded() + stats.duplicatesMerged() + stats.itinerariesProduced());
        }

        @Test
        @DisplayName("AC-23: no offers produce no itineraries and zero counters")
        void emptyAggregation() {
            AggregationResult result = aggregate(List.of());

            AggregationStatistics stats = result.statistics();
            assertThat(result.itineraries()).isEmpty();
            assertThat(stats.offersReceivedByProvider()).isEmpty();
            assertThat(stats.discardedByReason()).isEmpty();
            assertThat(stats.totalReceived()).isZero();
            assertThat(stats.totalDiscarded()).isZero();
            assertThat(stats.duplicatesMerged()).isZero();
            assertThat(stats.itinerariesProduced()).isZero();
            assertThat(stats.received(ALPHA)).isZero();
            assertThat(stats.discarded(DiscardReason.LAYOVER_TOO_LONG)).isZero();
        }
    }
}
