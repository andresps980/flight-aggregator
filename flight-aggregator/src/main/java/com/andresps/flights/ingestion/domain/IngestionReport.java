package com.andresps.flights.ingestion.domain;

import com.andresps.flights.aggregation.domain.deduplication.AggregationStatistics;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * What an ingestion run did (spec 003, AC-21): per provider, per day, and the spec 001 statistics summed over the
 * days.
 */
public record IngestionReport(
        UUID runId,
        Instant startedAt,
        Instant finishedAt,
        List<ProviderReport> providers,
        List<DayReport> days,
        AggregationStatistics statistics) {

    public IngestionReport {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        Objects.requireNonNull(statistics, "statistics");
        providers = List.copyOf(providers);
        days = List.copyOf(days);
    }

    public IngestionStatus status() {
        return IngestionStatus.overall(
                providers.stream().map(ProviderReport::status).toList());
    }

    public Duration duration() {
        return Duration.between(startedAt, finishedAt);
    }

    public ProviderReport provider(ProviderId provider) {
        return providers.stream()
                .filter(report -> report.provider().equals(provider))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("No report for provider " + provider));
    }

    public DayReport day(LocalDate day) {
        return days.stream()
                .filter(report -> report.day().equals(day))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("No report for day " + day));
    }
}
