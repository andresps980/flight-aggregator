package com.andresps.gdsmock.inventory.domain.stats;

import com.andresps.gdsmock.inventory.domain.model.InvalidKind;
import com.andresps.gdsmock.shared.domain.ProviderId;
import java.util.Map;

/**
 * Expected figures of a world (spec 002, AC-20).
 *
 * @param invalidItineraries per kind (every kind listed)
 * @param crossProviderDuplicates Σ over itineraries of (distinct providers − 1)
 * @param providers per provider (every provider listed)
 */
public record WorldStatistics(
        long distinctItineraries,
        Map<InvalidKind, Long> invalidItineraries,
        long crossProviderDuplicates,
        Map<ProviderId, ProviderStatistics> providers,
        FullyMalformedItineraries fullyMalformedItineraries,
        ExpectedAggregation expectedAggregation) {

    public WorldStatistics {
        invalidItineraries = Map.copyOf(invalidItineraries);
        providers = Map.copyOf(providers);
    }
}
