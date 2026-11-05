package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web;

import com.andresps.gdsmock.inventory.domain.model.Horizon;
import com.andresps.gdsmock.inventory.domain.stats.ExpectedAggregation;
import com.andresps.gdsmock.inventory.domain.stats.FullyMalformedItineraries;
import com.andresps.gdsmock.inventory.domain.stats.ProviderStatistics;
import com.andresps.gdsmock.inventory.domain.stats.WorldStatistics;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/** Body of {@code GET /admin/world/stats} (spec 002, AC-20). Maps are sorted by key for stable output. */
public record WorldStatsResponse(
        LocalDate referenceDate,
        HorizonResponse horizon,
        LocalDate date,
        long distinctItineraries,
        Map<String, Long> invalidItineraries,
        long crossProviderDuplicates,
        Map<String, ProviderResponse> providers,
        FullyMalformedResponse fullyMalformedItineraries,
        ExpectedAggregationResponse expectedAggregation) {

    public record HorizonResponse(LocalDate from, LocalDate to) {}

    public record ProviderResponse(
            long records, long sameProviderDuplicates, long fullyMalformedItineraries, Map<String, Long> malformed) {}

    public record FullyMalformedResponse(long total, Map<String, Long> byInvalidKind) {}

    public record ExpectedAggregationResponse(
            Map<String, Long> offersReceived,
            Map<String, Long> discarded,
            long duplicatesMerged,
            long itinerariesProduced) {}

    static WorldStatsResponse from(Horizon horizon, LocalDate date, WorldStatistics stats) {
        Map<String, ProviderResponse> providers = new TreeMap<>();
        stats.providers().forEach((id, p) -> providers.put(id.value(), provider(p)));
        FullyMalformedItineraries fully = stats.fullyMalformedItineraries();
        ExpectedAggregation expected = stats.expectedAggregation();
        return new WorldStatsResponse(
                horizon.referenceDate(),
                new HorizonResponse(horizon.from(), horizon.to()),
                date,
                stats.distinctItineraries(),
                byName(stats.invalidItineraries()),
                stats.crossProviderDuplicates(),
                providers,
                new FullyMalformedResponse(fully.total(), byName(fully.byInvalidKind())),
                new ExpectedAggregationResponse(
                        byName(expected.offersReceived()),
                        byName(expected.discarded()),
                        expected.duplicatesMerged(),
                        expected.itinerariesProduced()));
    }

    private static ProviderResponse provider(ProviderStatistics p) {
        return new ProviderResponse(
                p.records(), p.sameProviderDuplicates(), p.fullyMalformedItineraries(), byName(p.malformed()));
    }

    private static Map<String, Long> byName(Map<?, Long> counters) {
        Map<String, Long> sorted = new TreeMap<>();
        counters.forEach((key, value) -> sorted.put(key.toString(), value));
        return sorted;
    }
}
