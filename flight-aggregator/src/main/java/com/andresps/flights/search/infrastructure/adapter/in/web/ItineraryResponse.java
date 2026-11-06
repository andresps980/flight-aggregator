package com.andresps.flights.search.infrastructure.adapter.in.web;

import com.andresps.flights.search.domain.ItineraryView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "An aggregated itinerary")
public record ItineraryResponse(
        @Schema(
                description = "Stable identity: flight numbers, airports and departure instants",
                example = "IB6251:MAD-JFK@2026-11-08T10:15:00Z")
        String itineraryKey,

        @Schema(example = "MAD") String origin,
        @Schema(example = "JFK") String destination,

        @Schema(description = "UTC date of the first departure")
        LocalDate departureDate,

        Instant departureAt,
        Instant arrivalAt,

        @Schema(description = "Airline operating every segment", example = "IB")
        String carrier,

        int stops,
        List<SegmentResponse> segments,

        @Schema(description = "Cheapest provider price, EUR", example = "412.38")
        BigDecimal basePrice,

        @Schema(description = "Base price plus the OTA markup, EUR; the sort key", example = "433.00")
        BigDecimal finalPrice,

        @Schema(example = "EUR") String currency,

        @Schema(description = "Provider of the selected (cheapest) offer", example = "mock-alpha")
        String selectedProvider,

        @Schema(description = "Every provider that offered it")
        List<String> providers,

        FreshnessResponse freshness) {

    static final String CURRENCY = "EUR";

    static ItineraryResponse from(ItineraryView view) {
        return new ItineraryResponse(
                view.itineraryKey(),
                view.origin(),
                view.destination(),
                view.departureDate(),
                view.departureAt(),
                view.arrivalAt(),
                view.carrier(),
                view.stops(),
                view.segments().stream().map(SegmentResponse::from).toList(),
                view.basePrice(),
                view.finalPrice(),
                CURRENCY,
                view.selectedProvider(),
                view.providers(),
                FreshnessResponse.from(view.freshness()));
    }
}
