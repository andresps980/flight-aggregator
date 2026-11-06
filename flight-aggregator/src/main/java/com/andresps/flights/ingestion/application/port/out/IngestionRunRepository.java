package com.andresps.flights.ingestion.application.port.out;

import com.andresps.flights.ingestion.domain.IngestionRun;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persisted ingestion runs (spec 004, AC-14, AC-15, AC-22). */
public interface IngestionRunRepository {

    void create(IngestionRun run);

    /** Stores a run's new state; fails if the run does not exist. */
    void update(IngestionRun run);

    Optional<IngestionRun> findById(UUID id);

    /** The most recently started run. */
    Optional<IngestionRun> findLatest();

    /** The id of the most recent {@code RUNNING} run. */
    Optional<UUID> findRunning();

    /** Marks every {@code RUNNING} run {@code ABORTED} at {@code at}; returns how many. */
    int abortRunning(Instant at);

    /** Deletes runs started before {@code instant}; returns how many. */
    int deleteStartedBefore(Instant instant);
}
