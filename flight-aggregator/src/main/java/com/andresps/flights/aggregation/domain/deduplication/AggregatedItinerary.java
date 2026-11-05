package com.andresps.flights.aggregation.domain.deduplication;

import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.Itinerary;
import com.andresps.flights.aggregation.domain.model.ItineraryKey;
import com.andresps.flights.aggregation.domain.model.Money;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * One deduplicated itinerary: the selected (preferred) offer, its final price after markup, and every provider that
 * offered it, sorted by id.
 */
public record AggregatedItinerary(
        ItineraryKey key, FlightOffer selectedOffer, Money finalPrice, SortedSet<ProviderId> providers) {

    public AggregatedItinerary {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(selectedOffer, "selectedOffer");
        Objects.requireNonNull(finalPrice, "finalPrice");
        Objects.requireNonNull(providers, "providers");
        providers = Collections.unmodifiableSortedSet(new TreeSet<>(providers));
    }

    public Itinerary itinerary() {
        return selectedOffer.itinerary();
    }

    public Money basePrice() {
        return selectedOffer.basePrice();
    }
}
