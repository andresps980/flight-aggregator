package com.andresps.flights.aggregation.domain.deduplication;

import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.ItineraryKey;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.pricing.MarkupPolicy;
import com.andresps.flights.aggregation.domain.validation.DiscardReason;
import com.andresps.flights.aggregation.domain.validation.ItineraryValidator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Incremental deduplication (ADR-0011): offers are accepted one by one, e.g. straight from a streaming parser, and
 * only the preferred offer and the providers of each distinct valid itinerary are kept. Memory is therefore
 * O(distinct valid itineraries), not O(offers).
 *
 * <p>Invalid offers are counted under their {@link DiscardReason} and never compete in deduplication. The markup is
 * applied once per itinerary, to the selected base price, when the result is produced.
 *
 * <p>Not thread-safe: use one instance per aggregation (or per key partition) from a single thread. {@link #result()}
 * may be called at any time and reflects the offers accepted so far.
 */
public final class OfferAggregator {

    private final ItineraryValidator validator;
    private final MarkupPolicy markupPolicy;

    private final Map<ItineraryKey, Candidate> candidates = new HashMap<>();
    private final Map<ProviderId, Long> receivedByProvider = new HashMap<>();
    private final Map<DiscardReason, Long> discardedByReason = new EnumMap<>(DiscardReason.class);
    private long duplicatesMerged;

    public OfferAggregator(ItineraryValidator validator, MarkupPolicy markupPolicy) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.markupPolicy = Objects.requireNonNull(markupPolicy, "markupPolicy");
    }

    public void accept(FlightOffer offer) {
        Objects.requireNonNull(offer, "offer");
        receivedByProvider.merge(offer.provider(), 1L, Long::sum);

        Optional<DiscardReason> discardReason = validator.validate(offer.itinerary());
        if (discardReason.isPresent()) {
            discardedByReason.merge(discardReason.get(), 1L, Long::sum);
            return;
        }

        Candidate existing = candidates.putIfAbsent(offer.itinerary().key(), new Candidate(offer));
        if (existing != null) {
            existing.consider(offer);
            duplicatesMerged++;
        }
    }

    public AggregationResult result() {
        List<AggregatedItinerary> itineraries = candidates.entrySet().stream()
                .map(entry -> entry.getValue().toAggregated(entry.getKey(), markupPolicy))
                .toList();
        AggregationStatistics statistics =
                new AggregationStatistics(receivedByProvider, discardedByReason, duplicatesMerged, itineraries.size());
        return new AggregationResult(itineraries, statistics);
    }

    /** Running state for one itinerary key. */
    private static final class Candidate {

        private FlightOffer preferred;
        private final SortedSet<ProviderId> providers = new TreeSet<>();

        Candidate(FlightOffer offer) {
            this.preferred = offer;
            providers.add(offer.provider());
        }

        void consider(FlightOffer offer) {
            providers.add(offer.provider());
            if (OfferPreference.ORDER.compare(offer, preferred) < 0) {
                preferred = offer;
            }
        }

        AggregatedItinerary toAggregated(ItineraryKey key, MarkupPolicy markupPolicy) {
            return new AggregatedItinerary(key, preferred, markupPolicy.apply(preferred.basePrice()), providers);
        }
    }
}
