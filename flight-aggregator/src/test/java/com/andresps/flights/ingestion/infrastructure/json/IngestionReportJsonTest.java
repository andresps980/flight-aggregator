package com.andresps.flights.ingestion.infrastructure.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.aggregation.domain.deduplication.AggregationStatistics;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.validation.DiscardReason;
import com.andresps.flights.ingestion.domain.CircuitState;
import com.andresps.flights.ingestion.domain.DayPublication;
import com.andresps.flights.ingestion.domain.DayReport;
import com.andresps.flights.ingestion.domain.FailureKind;
import com.andresps.flights.ingestion.domain.IngestionReport;
import com.andresps.flights.ingestion.domain.MalformedReason;
import com.andresps.flights.ingestion.domain.PartitionFailure;
import com.andresps.flights.ingestion.domain.ProviderReport;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec 004 AC-14: the report is stored as JSON and read back unchanged. */
class IngestionReportJsonTest {

    private static final ProviderId ALPHA = new ProviderId("mock-alpha");
    private static final ProviderId BETA = new ProviderId("mock-beta");
    private static final LocalDate DAY = LocalDate.parse("2026-11-06");

    /** A report that uses every field: failures, malformed counts, discards, every day outcome kind. */
    public static IngestionReport fullReport(UUID runId) {
        Instant start = Instant.parse("2026-11-05T12:00:00.123456Z");
        ProviderReport alpha = new ProviderReport(
                ALPHA,
                2,
                2,
                List.of(),
                3,
                Duration.ofMillis(4321),
                CircuitState.CLOSED,
                120,
                Map.of(MalformedReason.BAD_AIRPORT_CODE, 2L, MalformedReason.UNPARSEABLE_TIME, 1L));
        ProviderReport beta = new ProviderReport(
                BETA,
                2,
                1,
                List.of(new PartitionFailure(DAY.plusDays(1), FailureKind.SERVER_ERROR, 3, "HTTP 500 \"quoted\"")),
                4,
                Duration.ofSeconds(9),
                CircuitState.HALF_OPEN,
                80,
                Map.of());
        AggregationStatistics day1 = new AggregationStatistics(
                Map.of(ALPHA, 60L, BETA, 80L), Map.of(DiscardReason.LAYOVER_TOO_LONG, 5L), 30, 105);
        AggregationStatistics day2 =
                new AggregationStatistics(Map.of(ALPHA, 60L), Map.of(DiscardReason.ALREADY_DEPARTED, 1L), 0, 59);
        return new IngestionReport(
                runId,
                start,
                start.plusSeconds(12),
                List.of(alpha, beta),
                List.of(
                        new DayReport(
                                DAY,
                                new TreeSet<>(List.of(ALPHA, BETA)),
                                new TreeSet<>(),
                                DayPublication.replaced(104, 1),
                                day1),
                        new DayReport(
                                DAY.plusDays(1),
                                new TreeSet<>(List.of(ALPHA)),
                                new TreeSet<>(List.of(BETA)),
                                DayPublication.keptStale(),
                                day2)),
                day1.plus(day2));
    }

    @Test
    @DisplayName("AC-14: a full report round-trips through JSON exactly")
    void roundTrip() {
        IngestionReport report = fullReport(UUID.randomUUID());

        String json = IngestionReportJson.write(report);

        assertThat(IngestionReportJson.read(json)).isEqualTo(report);
    }

    @Test
    @DisplayName("AC-14/AC-22: the JSON is readable: ISO instants, durations in ms, enums by name, derived totals")
    void readableShape() {
        String json = IngestionReportJson.write(fullReport(UUID.fromString("00000000-0000-0000-0000-000000000001")));

        assertThat(json)
                .contains("\"runId\":\"00000000-0000-0000-0000-000000000001\"")
                .contains("\"startedAt\":\"2026-11-05T12:00:00.123456Z\"")
                .contains("\"status\":\"PARTIAL\"")
                .contains("\"durationMs\":4321")
                .contains("\"kind\":\"SERVER_ERROR\"")
                .contains("\"outcome\":\"KEPT_STALE\"")
                .contains("\"storedItineraries\":104")
                .contains("\"misfiledItineraries\":1")
                .contains("\"LAYOVER_TOO_LONG\":5");
    }
}
