package com.andresps.flights.ingestion.application.port.in;

import com.andresps.flights.ingestion.domain.IngestionRun;
import java.util.Optional;
import java.util.UUID;

/** Reads persisted ingestion runs (spec 004, AC-22). */
public interface GetIngestionRunUseCase {

    Optional<IngestionRun> byId(UUID id);

    /** The most recently started run. */
    Optional<IngestionRun> latest();
}
