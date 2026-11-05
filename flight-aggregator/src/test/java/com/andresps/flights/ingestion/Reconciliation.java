package com.andresps.flights.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.aggregation.domain.deduplication.AggregationStatistics;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.validation.DiscardReason;
import com.andresps.flights.ingestion.domain.IngestionReport;
import com.andresps.flights.ingestion.domain.MalformedReason;
import com.andresps.flights.ingestion.domain.ProviderReport;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * AC-22: an ingestion report against gds-mock's {@code GET /admin/world/stats} (spec 002, AC-20): every figure must be
 * equal, not approximately equal.
 */
public final class Reconciliation {

    private Reconciliation() {}

    /** Whole run: every provider's records and malformed counts, and the summed aggregation statistics. */
    public static void assertReconciles(IngestionReport report, JsonNode stats) {
        report.providers().forEach(provider -> assertProviderReconciles(provider, stats));
        assertAggregationReconciles(report.statistics(), stats);
    }

    /** One provider's figures (used when other providers were deliberately failed). */
    public static void assertProviderReconciles(ProviderReport provider, JsonNode stats) {
        String id = provider.provider().value();
        JsonNode expected = stats.path("providers").path(id);
        assertThat(provider.recordsReceived())
                .as("%s records", id)
                .isEqualTo(expected.path("records").asLong());
        assertThat(provider.malformed())
                .as("%s malformed per reason", id)
                .isEqualTo(nonZero(expected.path("malformed"), MalformedReason::valueOf));
        assertThat(provider.offersReceived())
                .as("%s offers", id)
                .isEqualTo(stats.path("expectedAggregation")
                        .path("offersReceived")
                        .path(id)
                        .asLong());
    }

    /** The spec 001 statistics against {@code expectedAggregation}. */
    public static void assertAggregationReconciles(AggregationStatistics statistics, JsonNode stats) {
        JsonNode expected = stats.path("expectedAggregation");
        assertThat(statistics.offersReceivedByProvider())
                .as("offers received per provider")
                .isEqualTo(nonZero(expected.path("offersReceived"), ProviderId::new));
        assertThat(statistics.discardedByReason())
                .as("discarded per reason")
                .isEqualTo(nonZero(expected.path("discarded"), DiscardReason::valueOf));
        assertThat(statistics.duplicatesMerged())
                .as("duplicates merged")
                .isEqualTo(expected.path("duplicatesMerged").asLong());
        assertThat(statistics.itinerariesProduced())
                .as("itineraries produced")
                .isEqualTo(expected.path("itinerariesProduced").asLong());
    }

    private static <K> Map<K, Long> nonZero(JsonNode counts, java.util.function.Function<String, K> key) {
        Map<K, Long> result = new HashMap<>();
        counts.properties().forEach(entry -> {
            long value = entry.getValue().asLong();
            if (value > 0) {
                result.put(key.apply(entry.getKey()), value);
            }
        });
        return result;
    }
}
