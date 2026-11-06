package com.andresps.flights.ingestion.infrastructure.adapter.in.web;

import com.andresps.flights.ingestion.application.port.in.GetIngestionRunUseCase;
import com.andresps.flights.ingestion.application.port.in.StartIngestionUseCase;
import com.andresps.flights.ingestion.domain.IngestionRun;
import com.andresps.flights.ingestion.domain.IngestionTrigger;
import java.net.URI;
import java.util.UUID;
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
 * 9457 Problem Details.
 */
@RestController
@RequestMapping("/api/v1/ingestions")
class IngestionController {

    private final StartIngestionUseCase start;
    private final GetIngestionRunUseCase runs;

    IngestionController(StartIngestionUseCase start, GetIngestionRunUseCase runs) {
        this.start = start;
        this.runs = runs;
    }

    /** Starts a run; it executes asynchronously, follow it at the returned {@code Location}. */
    @PostMapping
    ResponseEntity<IngestionRunResponse> start() {
        IngestionRun run = start.start(IngestionTrigger.MANUAL).run();
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(run.id())
                .toUri();
        return ResponseEntity.accepted().location(location).body(IngestionRunResponse.from(run));
    }

    @GetMapping("/latest")
    IngestionRunResponse latest() {
        return runs.latest()
                .map(IngestionRunResponse::from)
                .orElseThrow(() -> new IngestionRunNotFoundException("No ingestion has run yet"));
    }

    @GetMapping("/{id}")
    IngestionRunResponse byId(@PathVariable UUID id) {
        return runs.byId(id)
                .map(IngestionRunResponse::from)
                .orElseThrow(() -> new IngestionRunNotFoundException("No ingestion " + id));
    }
}
