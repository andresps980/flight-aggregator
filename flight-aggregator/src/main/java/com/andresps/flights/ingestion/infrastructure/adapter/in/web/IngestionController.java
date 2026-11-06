package com.andresps.flights.ingestion.infrastructure.adapter.in.web;

import com.andresps.flights.ingestion.application.port.in.GetIngestionRunUseCase;
import com.andresps.flights.ingestion.application.port.in.StartIngestionUseCase;
import com.andresps.flights.ingestion.domain.IngestionRun;
import com.andresps.flights.ingestion.domain.IngestionTrigger;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Ingestion runs over REST (spec 004, ADR-0006). A run in progress answers {@code 409} through
 * {@code IngestionAlreadyRunningException}; unknown runs {@code 404}; a malformed id {@code 400}. All errors are RFC
 * 9457 Problem Details. The OpenAPI annotations describe exactly these responses (spec 006, backend follow-up).
 */
@RestController
@RequestMapping(value = "/api/v1/ingestions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Ingestion", description = "Ingestion runs: start one on demand and follow its status")
class IngestionController {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final StartIngestionUseCase start;
    private final GetIngestionRunUseCase runs;

    IngestionController(StartIngestionUseCase start, GetIngestionRunUseCase runs) {
        this.start = start;
        this.runs = runs;
    }

    /** Starts a run; it executes asynchronously, follow it at the returned {@code Location}. */
    @PostMapping
    @Operation(
            summary = "Start an ingestion run",
            description = "Starts a run in the background and returns it as RUNNING. Runs never overlap: while one is "
                    + "in progress (started by the schedule or by another request) the answer is 409.")
    @ApiResponse(
            responseCode = "202",
            description = "The run started; follow it at Location",
            headers = @Header(name = "Location", description = "URL of the run", schema = @Schema(type = "string")))
    @ApiResponse(
            responseCode = "409",
            description = "A run is already in progress",
            content =
                    @Content(
                            mediaType = PROBLEM_JSON,
                            schema = @Schema(implementation = IngestionAlreadyRunningProblem.class)))
    ResponseEntity<IngestionRunResponse> start() {
        IngestionRun run = start.start(IngestionTrigger.MANUAL).run();
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(run.id())
                .toUri();
        return ResponseEntity.accepted().location(location).body(IngestionRunResponse.from(run));
    }

    @GetMapping("/latest")
    @Operation(
            summary = "Latest ingestion run",
            description = "The most recently started run, in any status (RUNNING included).")
    @ApiResponse(responseCode = "200", description = "The latest run")
    @ApiResponse(
            responseCode = "404",
            description = "No ingestion has run yet",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    IngestionRunResponse latest() {
        return runs.latest()
                .map(IngestionRunResponse::from)
                .orElseThrow(() -> new IngestionRunNotFoundException("No ingestion has run yet"));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Ingestion run by id")
    @ApiResponse(responseCode = "200", description = "The run")
    @ApiResponse(
            responseCode = "400",
            description = "The id is not a UUID",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No run with this id (runs are kept for 7 days)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    IngestionRunResponse byId(@Parameter(description = "Run id, as returned by POST") @PathVariable UUID id) {
        return runs.byId(id)
                .map(IngestionRunResponse::from)
                .orElseThrow(() -> new IngestionRunNotFoundException("No ingestion " + id));
    }
}
