package com.andresps.gdsmock.inventory.domain.model;

import com.andresps.gdsmock.shared.domain.ProviderId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An itinerary of the generated world and every provider record that offers it.
 *
 * @param partitionDay the horizon day it was generated for (served under it with {@code ?date=})
 * @param index position within the partition day
 * @param invalidKind present if the itinerary is business-invalid on purpose
 * @param records the provider records, in provider order (a same-provider duplicate follows its original)
 */
public record WorldItinerary(
        LocalDate partitionDay,
        int index,
        List<ScheduledSegment> segments,
        BigDecimal baseFare,
        Optional<InvalidKind> invalidKind,
        List<ProviderRecord> records) {

    public WorldItinerary {
        Objects.requireNonNull(partitionDay, "partitionDay");
        Objects.requireNonNull(baseFare, "baseFare");
        Objects.requireNonNull(invalidKind, "invalidKind");
        segments = List.copyOf(segments);
        records = List.copyOf(records);
    }

    /** The distinct providers offering this itinerary, in record order. */
    public Set<ProviderId> providers() {
        Set<ProviderId> providers = new LinkedHashSet<>();
        records.forEach(r -> providers.add(r.provider()));
        return providers;
    }
}
