package com.andresps.flights.search.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** The freshness of an itinerary's day, from {@code day_snapshot}. {@code staleSince} is null unless stale. */
public record Freshness(
        FreshnessStatus status, Instant publishedAt, List<String> missingProviders, Instant staleSince) {

    public Freshness {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(publishedAt, "publishedAt");
        missingProviders = List.copyOf(missingProviders);
    }

    /** Stale wins over incomplete, which wins over complete. */
    public static Freshness of(
            boolean complete, Instant publishedAt, List<String> missingProviders, Instant staleSince) {
        FreshnessStatus status = staleSince != null
                ? FreshnessStatus.STALE
                : complete ? FreshnessStatus.COMPLETE : FreshnessStatus.INCOMPLETE;
        return new Freshness(status, publishedAt, missingProviders, staleSince);
    }
}
