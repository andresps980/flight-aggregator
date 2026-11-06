package com.andresps.flights.search.infrastructure.adapter.in.web;

import com.andresps.flights.search.domain.Freshness;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "Freshness of the itinerary's departure day")
public record FreshnessResponse(
        @Schema(
                description = "COMPLETE: every provider; INCOMPLETE: some providers missing; STALE: the latest "
                        + "run could not refresh the day, previous data kept (takes precedence)",
                allowableValues = {"COMPLETE", "INCOMPLETE", "STALE"})
        String status,

        @Schema(description = "When the day's data was written")
        Instant publishedAt,

        List<String> missingProviders,

        @Schema(description = "Since when the day is stale; null unless STALE", nullable = true)
        Instant staleSince) {

    static FreshnessResponse from(Freshness freshness) {
        return new FreshnessResponse(
                freshness.status().name(),
                freshness.publishedAt(),
                freshness.missingProviders(),
                freshness.staleSince());
    }
}
