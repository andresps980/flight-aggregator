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
 *
 * <p>Holds at most {@code maxRecords} records: a provider streaming without end (a bug upstream) fails the attempt as
 * a malformed document instead of exhausting the heap.
 */
public final class PartitionBuffer {

    private final List<FlightOffer> offers = new ArrayList<>();
    private final Map<MalformedReason, Long> malformed = new EnumMap<>(MalformedReason.class);
    private final long maxRecords;
    private long records;

    public PartitionBuffer(long maxRecords) {
        if (maxRecords < 1) {
            throw new IllegalArgumentException("maxRecords must be at least 1, got " + maxRecords);
        }
        this.maxRecords = maxRecords;
    }

    /** Without a record limit (tests and tools). */
    public PartitionBuffer() {
        this(Long.MAX_VALUE);
    }

    public void addOffer(FlightOffer offer) {
        count();
        offers.add(offer);
    }

    public void addMalformed(MalformedReason reason) {
        count();
        malformed.merge(reason, 1L, Long::sum);
    }

    private void count() {
        if (++records > maxRecords) {
            throw new MalformedDocumentException("Partition has more than " + maxRecords + " records");
        }
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
