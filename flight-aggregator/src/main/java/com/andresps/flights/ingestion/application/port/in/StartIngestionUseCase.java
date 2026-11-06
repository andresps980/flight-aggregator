package com.andresps.flights.ingestion.application.port.in;

import com.andresps.flights.ingestion.domain.IngestionTrigger;

/**
 * Starts an ingestion run unless one is in progress in any instance (spec 004, ADR-0006, ADR-0019). The run is stored
 * as {@code RUNNING} before this returns and executes asynchronously.
 *
 * @throws IngestionAlreadyRunningException if a run is in progress
 */
public interface StartIngestionUseCase {

    StartedIngestion start(IngestionTrigger trigger);
}
