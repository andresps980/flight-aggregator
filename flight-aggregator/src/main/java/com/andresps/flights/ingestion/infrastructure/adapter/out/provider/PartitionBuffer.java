package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.ingestion.domain.MalformedReason;
import com.andresps.flights.ingestion.domain.ProviderPartition;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What one attempt has parsed so far (ADR-0015). Each attempt gets a new buffer; it becomes a {@link ProviderPartition}
 * only if the attempt completes, and is dropped otherwise, so a failed attempt never leaves offers behind and a retry
 * never duplicates them. Not thread-safe: one attempt, one thread.
 */
public final class PartitionBuffer {

    private final List<FlightOffer> offers = new ArrayList<>();
    private final Map<MalformedReason, Long> malformed = new EnumMap<>(MalformedReason.class);

    public void addOffer(FlightOffer offer) {
        offers.add(offer);
    }

    public void addMalformed(MalformedReason reason) {
        malformed.merge(reason, 1L, Long::sum);
    }

    public List<FlightOffer> offers() {
        return Collections.unmodifiableList(offers);
    }

    public Map<MalformedReason, Long> malformed() {
        return Map.copyOf(malformed);
    }

    public long malformed(MalformedReason reason) {
        return malformed.getOrDefault(reason, 0L);
    }

    public long recordsReceived() {
        return offers.size()
                + malformed.values().stream().mapToLong(Long::longValue).sum();
    }

    public ProviderPartition toPartition(ProviderId provider, LocalDate day, int attempts) {
        return new ProviderPartition(provider, day, offers, malformed, attempts);
    }
}
