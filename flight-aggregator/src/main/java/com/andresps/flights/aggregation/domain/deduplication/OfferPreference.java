package com.andresps.flights.aggregation.domain.deduplication;

import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.Segment;
import java.util.Comparator;
import java.util.List;

/**
 * Total order of offers for the same itinerary: the first one is selected (spec 001, AC-4 – AC-8; ADR-0011).
 *
 * <ol>
 *   <li>lowest base price;
 *   <li>provider id, ascending;
 *   <li>earliest arrival times, segment by segment (arrival is not part of the identity, so providers may disagree);
 *   <li>canonical itinerary representation, ascending.
 * </ol>
 *
 * Because the order is total, keeping its minimum gives the same winner whatever order offers arrive in.
 */
public final class OfferPreference {

    public static final Comparator<FlightOffer> ORDER = Comparator.comparing(FlightOffer::basePrice)
            .thenComparing(FlightOffer::provider)
            .thenComparing(OfferPreference::compareArrivals)
            .thenComparing(offer -> offer.itinerary().canonicalForm());

    private OfferPreference() {}

    private static int compareArrivals(FlightOffer first, FlightOffer second) {
        List<Segment> a = first.itinerary().segments();
        List<Segment> b = second.itinerary().segments();
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int comparison = a.get(i).arrival().compareTo(b.get(i).arrival());
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(a.size(), b.size());
    }
}
