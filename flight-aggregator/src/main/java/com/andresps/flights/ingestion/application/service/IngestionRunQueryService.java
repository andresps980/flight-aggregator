package com.andresps.flights.ingestion.application.service;

import com.andresps.flights.ingestion.application.port.in.GetIngestionRunUseCase;
import com.andresps.flights.ingestion.application.port.out.IngestionRunRepository;
import com.andresps.flights.ingestion.domain.IngestionRun;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Reads persisted runs (spec 004, AC-22). */
public final class IngestionRunQueryService implements GetIngestionRunUseCase {

    private final IngestionRunRepository runs;

    public IngestionRunQueryService(IngestionRunRepository runs) {
        this.runs = Objects.requireNonNull(runs, "runs");
    }

    @Override
    public Optional<IngestionRun> byId(UUID id) {
        return runs.findById(id);
    }

    @Override
    public Optional<IngestionRun> latest() {
        return runs.findLatest();
    }
}
