package com.andresps.flights.ingestion.application.port.out;

import com.andresps.flights.ingestion.domain.FailureKind;
import java.util.Objects;

/** A partition could not be fetched: why, and after how many HTTP attempts (0 when no request was made). */
public class PartitionFetchException extends RuntimeException {

    private final FailureKind kind;
    private final int attempts;

    public PartitionFetchException(FailureKind kind, int attempts, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.attempts = attempts;
    }

    public FailureKind kind() {
        return kind;
    }

    public int attempts() {
        return attempts;
    }
}
