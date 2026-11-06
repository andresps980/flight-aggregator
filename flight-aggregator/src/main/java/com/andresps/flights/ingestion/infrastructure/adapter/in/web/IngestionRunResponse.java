package com.andresps.flights.ingestion.infrastructure.adapter.in.web;

import com.andresps.flights.ingestion.domain.IngestionRun;
import com.andresps.flights.ingestion.infrastructure.json.IngestionReportJson.ReportDocument;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * An ingestion run as returned by {@code /api/v1/ingestions} (spec 004, AC-21, AC-22). {@code finishedAt},
 * {@code durationMs}, {@code failure} and {@code report} are {@code null} until they apply; the report is the same
 * document that is stored.
 */
public record IngestionRunResponse(
        String id,
        String trigger,
        String status,
        String startedAt,
        String finishedAt,
        Long durationMs,
        String failure,
        ReportDocument report) {

    static IngestionRunResponse from(IngestionRun run) {
        return new IngestionRunResponse(
                run.id().toString(),
                run.trigger().name(),
                run.status().name(),
                run.startedAt().toString(),
                Optional.ofNullable(run.finishedAt()).map(Instant::toString).orElse(null),
                run.duration().map(Duration::toMillis).orElse(null),
                run.failure(),
                run.report() == null ? null : ReportDocument.of(run.report()));
    }
}
