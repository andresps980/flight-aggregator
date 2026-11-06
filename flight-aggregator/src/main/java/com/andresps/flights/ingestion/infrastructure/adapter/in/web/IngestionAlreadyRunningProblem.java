package com.andresps.flights.ingestion.infrastructure.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Documentation only: the shape of the {@code 409} answered by {@code POST /api/v1/ingestions}. The body itself is a
 * Spring {@code ProblemDetail} built by {@code GlobalExceptionHandler}, which writes extra properties such as
 * {@code runningIngestionId} next to the standard fields; this record lets the OpenAPI description (and the clients
 * generated from it) name that property.
 */
@Schema(description = "RFC 9457 problem detail of a POST refused because a run is in progress")
record IngestionAlreadyRunningProblem(
        @Schema(example = "about:blank", requiredMode = Schema.RequiredMode.REQUIRED)
        String type,

        @Schema(example = "Ingestion already running", requiredMode = Schema.RequiredMode.REQUIRED)
        String title,

        @Schema(example = "409", requiredMode = Schema.RequiredMode.REQUIRED)
        int status,

        @Schema(
                example = "Ingestion 46369dfb-0078-4b6e-b27d-5d3e7c96e870 is in progress",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String detail,

        @Schema(example = "/api/v1/ingestions", requiredMode = Schema.RequiredMode.REQUIRED)
        String instance,

        @Schema(
                description = "Id of the run in progress; absent in the few milliseconds after it took the lock and "
                        + "before its row is visible",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                example = "46369dfb-0078-4b6e-b27d-5d3e7c96e870")
        String runningIngestionId) {}
