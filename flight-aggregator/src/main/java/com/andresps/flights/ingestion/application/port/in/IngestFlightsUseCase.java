package com.andresps.flights.ingestion.application.port.in;

import com.andresps.flights.ingestion.domain.IngestionReport;
import java.util.UUID;

/**
 * Runs one ingestion: fetches every (provider, day) partition of the horizon, aggregates each day and publishes it
 * (spec 003). Never throws for provider failures: they are reported. Must not be invoked concurrently: the run
 * service ({@link StartIngestionUseCase}) guards it with a cross-instance lock (ADR-0019).
 */
public interface IngestFlightsUseCase {

    /**
     * @param runId the run's id, chosen by the caller so that it is known before the run ends
     * @param guard asked before each day is published; once it answers {@code false} nothing more is published and
     *     the call ends with {@link RunLockLostException}
     */
    IngestionReport ingest(UUID runId, PublicationGuard guard);
}
