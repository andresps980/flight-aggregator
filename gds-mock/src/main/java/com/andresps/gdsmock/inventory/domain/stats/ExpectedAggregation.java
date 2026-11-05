package com.andresps.gdsmock.inventory.domain.stats;

import com.andresps.gdsmock.inventory.domain.model.InvalidKind;
import com.andresps.gdsmock.shared.domain.ProviderId;
import java.util.Map;

/**
 * What spec 001's aggregation should report when fed every well-formed record (malformed ones never reach it), while
 * its clock is on the reference date. {@code Σ offersReceived = Σ discarded + duplicatesMerged + itinerariesProduced}.
 *
 * @param discarded well-formed records of invalid itineraries, per kind (every kind listed)
 * @param duplicatesMerged valid well-formed records that did not produce a new itinerary
 */
public record ExpectedAggregation(
        Map<ProviderId, Long> offersReceived,
        Map<InvalidKind, Long> discarded,
        long duplicatesMerged,
        long itinerariesProduced) {

    public ExpectedAggregation {
        offersReceived = Map.copyOf(offersReceived);
        discarded = Map.copyOf(discarded);
    }
}
