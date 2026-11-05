package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

import com.andresps.flights.ingestion.domain.FailureKind;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One attempt failed. Whether it is retried and whether it counts against the provider's circuit breaker depend only on
 * its kind: 5xx, timeouts, I/O errors and malformed documents do both; a 4xx or a cancellation does neither.
 */
public final class AttemptFailedException extends RuntimeException {

    private static final Set<FailureKind> PROVIDER_FAILURES = EnumSet.of(
            FailureKind.SERVER_ERROR, FailureKind.TIMEOUT, FailureKind.IO_ERROR, FailureKind.MALFORMED_DOCUMENT);

    private final FailureKind kind;

    public AttemptFailedException(FailureKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public FailureKind kind() {
        return kind;
    }

    /** A failure of the provider (not of our request, not a cancellation): retried and recorded by the breaker. */
    public boolean retryable() {
        return PROVIDER_FAILURES.contains(kind);
    }
}
