package com.andresps.flights.ingestion.infrastructure.adapter.in.web;

import com.andresps.flights.ingestion.domain.IngestionRun;
import com.andresps.flights.ingestion.infrastructure.json.IngestionReportJson.ReportDocument;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * An ingestion run as returned by {@code /api/v1/ingestions} (spec 004, AC-21, AC-22). {@code finishedAt},
 * {@code durationMs}, {@code failure} and {@code report} are {@code null} until they apply; the report is the same
 * document that is stored.
 */
@Schema(description = "An ingestion run")
public record IngestionRunResponse(
        @Schema(description = "Run id (UUID)", example = "46369dfb-0078-4b6e-b27d-5d3e7c96e870")
        String id,

        @Schema(
                description = "What started the run",
                allowableValues = {"SCHEDULED", "MANUAL"})
        String trigger,

        @Schema(
                description = "RUNNING; COMPLETED; PARTIAL: some partition failed or a day could not be written; "
                        + "FAILED: every provider failed, an unexpected error, or the run lost its lock; "
                        + "ABORTED: its process died (marked by the next run)",
                allowableValues = {"RUNNING", "COMPLETED", "PARTIAL", "FAILED", "ABORTED"})
        String status,

        @Schema(format = "date-time") String startedAt,

        @Schema(format = "date-time", description = "Null while RUNNING", nullable = true)
        String finishedAt,

        @Schema(description = "Null while RUNNING", nullable = true)
        Long durationMs,

        @Schema(
                description = "Generic failure message (details are in the log); set for FAILED and ABORTED runs",
                nullable = true)
        String failure,

        @Schema(
                description =
                        "The ingestion report: set once the run finished normally (COMPLETED, PARTIAL, or FAILED because "
                                + "every provider failed); null while RUNNING and for runs that failed or aborted without one",
                nullable = true)
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
