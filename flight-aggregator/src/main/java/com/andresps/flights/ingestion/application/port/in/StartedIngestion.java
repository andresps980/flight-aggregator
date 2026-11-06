package com.andresps.flights.ingestion.application.port.in;

import com.andresps.flights.ingestion.domain.IngestionRun;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** A run that has started: its {@code RUNNING} state, and its final state once it ends (never completes exceptionally). */
public record StartedIngestion(IngestionRun run, CompletableFuture<IngestionRun> completion) {

    public StartedIngestion {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(completion, "completion");
    }
}
