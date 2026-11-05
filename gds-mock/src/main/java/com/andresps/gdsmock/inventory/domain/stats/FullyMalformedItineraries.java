package com.andresps.gdsmock.inventory.domain.stats;

import com.andresps.gdsmock.inventory.domain.model.InvalidKind;
import java.util.Map;

/**
 * Itineraries whose records are all malformed: no client can parse them, so the aggregator never sees them.
 *
 * @param byInvalidKind the invalid ones among them, per kind (every kind listed)
 */
public record FullyMalformedItineraries(long total, Map<InvalidKind, Long> byInvalidKind) {

    public FullyMalformedItineraries {
        byInvalidKind = Map.copyOf(byInvalidKind);
    }
}
