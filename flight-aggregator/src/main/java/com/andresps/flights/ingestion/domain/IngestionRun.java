package com.andresps.flights.ingestion.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A persisted ingestion run (spec 004): {@code RUNNING} from its start, then finished with the report, failed with a
 * message, or aborted when found orphaned. {@code finishedAt}, {@code report} and {@code failure} are {@code null}
 * until they apply.
 */
public record IngestionRun(
        UUID id,
        IngestionTrigger trigger,
        RunStatus status,
        Instant startedAt,
        Instant finishedAt,
        IngestionReport report,
        String failure) {

    /** The failure recorded for a run found {@code RUNNING} after its process died. */
    public static final String ABORTED_FAILURE = "process ended before the run finished";

    public IngestionRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        if ((status == RunStatus.RUNNING) != (finishedAt == null)) {
            throw new IllegalArgumentException("Only a RUNNING run has no end instant, got " + status);
        }
    }

    public static IngestionRun started(UUID id, IngestionTrigger trigger, Instant at) {
        return new IngestionRun(id, trigger, RunStatus.RUNNING, at, null, null, null);
    }

    public IngestionRun finished(IngestionReport report, Instant at) {
        return new IngestionRun(id, trigger, RunStatus.of(report.status()), startedAt, at, report, null);
    }

    public IngestionRun failed(String message, Instant at) {
        return new IngestionRun(id, trigger, RunStatus.FAILED, startedAt, at, null, message);
    }

    public IngestionRun aborted(Instant at) {
        return new IngestionRun(id, trigger, RunStatus.ABORTED, startedAt, at, null, ABORTED_FAILURE);
    }

    public Optional<Duration> duration() {
        return Optional.ofNullable(finishedAt).map(end -> Duration.between(startedAt, end));
    }
}
