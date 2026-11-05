package com.andresps.flights.ingestion.application.port.in;

import com.andresps.flights.ingestion.domain.IngestionReport;

/**
 * Runs one ingestion: fetches every (provider, day) partition of the horizon, aggregates each day and publishes it
 * (spec 003). Never throws for provider failures: they are reported. Must not be invoked concurrently; the guard
 * against overlapping runs belongs to the trigger (ADR-0006, next spec).
 */
public interface IngestFlightsUseCase {

    IngestionReport ingest();
}
