package com.andresps.flights.ingestion.domain;

import static com.andresps.flights.aggregation.domain.FlightFixtures.ALPHA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.BETA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.aggregation.domain.deduplication.AggregationStatistics;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AC-21: derived figures of the report. */
class IngestionReportTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-06");

    @Test
    @DisplayName("AC-21: records received = offers received + malformed, and partitions failed = failures listed")
    void providerFigures() {
        ProviderReport report = new ProviderReport(
                ALPHA,
                3,
                2,
                List.of(new PartitionFailure(DAY, FailureKind.SERVER_ERROR, 3, "HTTP 500")),
                5,
                Duration.ofSeconds(2),
                CircuitState.CLOSED,
                120,
                Map.of(MalformedReason.BAD_AIRPORT_CODE, 2L, MalformedReason.UNPARSEABLE_TIME, 1L));

        assertThat(report.status()).isEqualTo(IngestionStatus.PARTIAL);
        assertThat(report.partitionsFailed()).isEqualTo(1);
        assertThat(report.malformedTotal()).isEqualTo(3);
        assertThat(report.recordsReceived()).isEqualTo(123);
        assertThat(report.malformed(MalformedReason.BAD_AIRPORT_CODE)).isEqualTo(2);
        assertThat(report.malformed(MalformedReason.NON_POSITIVE_PRICE)).isZero();
    }

    @Test
    @DisplayName("AC-21: succeeded + failed partitions must add up to the partitions requested")
    void partitionCountsMustAddUp() {
        assertThatThrownBy(() -> new ProviderReport(
                        ALPHA,
                        3,
                        3,
                        List.of(new PartitionFailure(DAY, FailureKind.TIMEOUT, 3, "")),
                        4,
                        Duration.ZERO,
                        CircuitState.CLOSED,
                        0,
                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AC-19/AC-21: the run status, duration and lookups derive from the provider reports")
    void runFigures() {
        ProviderReport alpha = completed(ALPHA);
        ProviderReport beta = new ProviderReport(
                BETA,
                1,
                0,
                List.of(new PartitionFailure(DAY, FailureKind.CIRCUIT_OPEN, 0, "open")),
                0,
                Duration.ZERO,
                CircuitState.OPEN,
                0,
                Map.of());
        Instant start = Instant.parse("2026-11-05T12:00:00Z");

        IngestionReport report = new IngestionReport(
                UUID.randomUUID(),
                start,
                start.plusSeconds(15),
                List.of(alpha, beta),
                List.of(),
                AggregationStatistics.empty());

        assertThat(report.status()).isEqualTo(IngestionStatus.PARTIAL);
        assertThat(report.duration()).isEqualTo(Duration.ofSeconds(15));
        assertThat(report.provider(BETA)).isSameAs(beta);
    }

    private static ProviderReport completed(com.andresps.flights.aggregation.domain.model.ProviderId provider) {
        return new ProviderReport(
                provider, 1, 1, List.of(), 1, Duration.ofSeconds(1), CircuitState.CLOSED, 10, Map.of());
    }
}
