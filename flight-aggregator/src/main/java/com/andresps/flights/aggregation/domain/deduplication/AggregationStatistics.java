package com.andresps.flights.aggregation.domain.deduplication;

import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.validation.DiscardReason;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Counters of one aggregation. Every received offer ends up in exactly one bucket, so
 * {@code totalReceived() == totalDiscarded() + duplicatesMerged() + itinerariesProduced()}. All counters are additive,
 * so statistics of disjoint partitions can be summed.
 *
 * @param offersReceivedByProvider offers accepted per provider, valid or not
 * @param discardedByReason invalid offers per reason (reasons with no discards are absent)
 * @param duplicatesMerged valid offers that matched an itinerary already seen
 * @param itinerariesProduced distinct valid itineraries
 */
public record AggregationStatistics(
        Map<ProviderId, Long> offersReceivedByProvider,
        Map<DiscardReason, Long> discardedByReason,
        long duplicatesMerged,
        long itinerariesProduced) {

    public AggregationStatistics {
        offersReceivedByProvider = Map.copyOf(offersReceivedByProvider);
        discardedByReason = Map.copyOf(discardedByReason);
    }

    /** No offers: the identity of {@link #plus}. */
    public static AggregationStatistics empty() {
        return new AggregationStatistics(Map.of(), Map.of(), 0, 0);
    }

    /**
     * Counter-wise sum, for aggregations of disjoint key partitions (spec 003 aggregates one day at a time). Only
     * meaningful when no itinerary key appears in both; otherwise a duplicate across them would count twice.
     */
    public AggregationStatistics plus(AggregationStatistics other) {
        Map<ProviderId, Long> received = new HashMap<>(offersReceivedByProvider);
        other.offersReceivedByProvider.forEach((provider, count) -> received.merge(provider, count, Long::sum));
        Map<DiscardReason, Long> discarded = new EnumMap<>(DiscardReason.class);
        discarded.putAll(discardedByReason);
        other.discardedByReason.forEach((reason, count) -> discarded.merge(reason, count, Long::sum));
        return new AggregationStatistics(
                received,
                discarded,
                duplicatesMerged + other.duplicatesMerged,
                itinerariesProduced + other.itinerariesProduced);
    }

    public long received(ProviderId provider) {
        return offersReceivedByProvider.getOrDefault(provider, 0L);
    }

    public long discarded(DiscardReason reason) {
        return discardedByReason.getOrDefault(reason, 0L);
    }

    public long totalReceived() {
        return offersReceivedByProvider.values().stream()
                .mapToLong(Long::longValue)
                .sum();
    }

    public long totalDiscarded() {
        return discardedByReason.values().stream().mapToLong(Long::longValue).sum();
    }
}
