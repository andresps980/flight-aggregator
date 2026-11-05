package com.andresps.gdsmock.inventory.domain.stats;

import com.andresps.gdsmock.inventory.domain.model.MalformationKind;
import java.util.Map;

/**
 * Figures for one provider.
 *
 * @param records records served (offers + same-provider duplicates)
 * @param fullyMalformedItineraries itineraries this provider offers whose records from it are all malformed
 * @param malformed malformed records per kind (every kind listed)
 */
public record ProviderStatistics(
        long records,
        long sameProviderDuplicates,
        long fullyMalformedItineraries,
        Map<MalformationKind, Long> malformed) {

    public ProviderStatistics {
        malformed = Map.copyOf(malformed);
    }
}
