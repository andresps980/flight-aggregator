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

    /**
     * The providers' overall status (spec 003, AC-19), except that a day whose write failed makes a {@code COMPLETED}
     * run {@code PARTIAL} (spec 004, AC-7).
     */
    public IngestionStatus status() {
        IngestionStatus byProviders = IngestionStatus.overall(
                providers.stream().map(ProviderReport::status).toList());
        boolean writeFailed = days.stream().anyMatch(day -> day.publication().outcome() == DayOutcome.WRITE_FAILED);
        return byProviders == IngestionStatus.COMPLETED && writeFailed ? IngestionStatus.PARTIAL : byProviders;
    }

    /** Rows stored by this run, over all days. */
    public long storedItineraries() {
        return days.stream()
                .mapToLong(day -> day.publication().storedItineraries())
                .sum();
    }

    /** Itineraries not stored because their first departure is on another UTC day (spec 004, AC-9). */
    public long misfiledItineraries() {
        return days.stream()
                .mapToLong(day -> day.publication().misfiledItineraries())
                .sum();
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
