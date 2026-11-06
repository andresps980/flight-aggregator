package com.andresps.flights.ingestion.application.port.in;

import com.andresps.flights.shared.domain.ConflictException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A run could not start because another one holds the lock (spec 004, AC-17, AC-21). */
public class IngestionAlreadyRunningException extends ConflictException {

    private final transient Optional<UUID> runningId;

    public IngestionAlreadyRunningException(Optional<UUID> runningId) {
        super(
                "Ingestion already running",
                runningId.map(id -> "Ingestion " + id + " is in progress").orElse("An ingestion is in progress"),
                runningId
                        .<Map<String, Object>>map(id -> Map.of("runningIngestionId", id.toString()))
                        .orElse(Map.of()));
        this.runningId = runningId;
    }

    /** The running run's id, when its row is already visible (it may not be for a few milliseconds after locking). */
    public Optional<UUID> runningId() {
        return runningId;
    }
}
