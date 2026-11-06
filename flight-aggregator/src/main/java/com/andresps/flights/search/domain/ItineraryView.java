package com.andresps.flights.search.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A searchable itinerary as the read model stores it (spec 004), with its day's freshness. The internal row id is not
 * part of it: it changes on every snapshot; {@code itineraryKey} is the stable identity.
 */
public record ItineraryView(
        String itineraryKey,
        String origin,
        String destination,
        LocalDate departureDate,
        Instant departureAt,
        Instant arrivalAt,
        String carrier,
        int stops,
        List<SegmentView> segments,
        BigDecimal basePrice,
        BigDecimal finalPrice,
        String selectedProvider,
        List<String> providers,
        Freshness freshness) {

    public ItineraryView {
        segments = List.copyOf(segments);
        providers = List.copyOf(providers);
    }

    /** Departed = first departure not strictly after {@code now} (spec 001 Q5). */
    public boolean departedAt(Instant now) {
        return !departureAt.isAfter(now);
    }
}
