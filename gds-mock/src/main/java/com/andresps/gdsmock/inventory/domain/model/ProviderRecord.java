package com.andresps.gdsmock.inventory.domain.model;

import com.andresps.gdsmock.shared.domain.ProviderId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One provider's offer of a world itinerary, as it will be serialized.
 *
 * @param recordId {@code yyyyMMdd-NNNNNN-c}: partition day, itinerary index and copy; formats add their own prefix
 * @param copy 0 for the offer, 1 for an injected same-provider duplicate
 * @param segments the itinerary's segments, with a corrupted airport code if the record is malformed that way
 * @param price EUR, scale 2; zero or negative only if the record is malformed that way
 */
public record ProviderRecord(
        ProviderId provider,
        String recordId,
        int copy,
        List<ScheduledSegment> segments,
        BigDecimal price,
        Optional<Malformation> malformation) {

    public ProviderRecord {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(malformation, "malformation");
        segments = List.copyOf(segments);
    }

    public boolean malformed() {
        return malformation.isPresent();
    }

    /** Index of the segment whose departure time must be rendered unparseable, or -1. */
    public int unparseableTimeIndex() {
        return malformation
                .filter(m -> m.kind() == MalformationKind.UNPARSEABLE_TIME)
                .map(Malformation::segmentIndex)
                .orElse(-1);
    }
}
