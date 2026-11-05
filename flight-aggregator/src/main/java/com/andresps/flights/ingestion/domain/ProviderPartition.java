package com.andresps.flights.ingestion.domain;

import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One provider's day, fully fetched and parsed by a successful attempt (ADR-0015): its well-formed offers and the count
 * of malformed records per reason. A partition is all or nothing; a failed attempt never produces one.
 *
 * @param attempts HTTP attempts made, including failed ones before the successful one
 */
public record ProviderPartition(
        ProviderId provider,
        LocalDate day,
        List<FlightOffer> offers,
        Map<MalformedReason, Long> malformed,
        int attempts) {

    public ProviderPartition {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(day, "day");
        offers = List.copyOf(offers);
        malformed = MalformedCounts.nonZero(malformed);
        if (attempts < 1) {
            throw new IllegalArgumentException("A fetched partition needs at least one attempt, got " + attempts);
        }
    }

    public long malformedTotal() {
        return MalformedCounts.total(malformed);
    }

    public long recordsReceived() {
        return offers.size() + malformedTotal();
    }
}
