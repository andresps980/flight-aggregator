package com.andresps.flights.ingestion.infrastructure.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.ingestion.domain.IngestionReport;
import com.andresps.flights.support.ReportFixtures;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec 004 AC-14: the report is stored as JSON and read back unchanged. */
class IngestionReportJsonTest {

    @Test
    @DisplayName("AC-14: a full report round-trips through JSON exactly")
    void roundTrip() {
        IngestionReport report = ReportFixtures.fullReport(UUID.randomUUID());

        String json = IngestionReportJson.write(report);

        assertThat(IngestionReportJson.read(json)).isEqualTo(report);
    }

    @Test
    @DisplayName("AC-14/AC-22: the JSON is readable: ISO instants, durations in ms, enums by name, derived totals")
    void readableShape() {
        String json = IngestionReportJson.write(
                ReportFixtures.fullReport(UUID.fromString("00000000-0000-0000-0000-000000000001")));

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
