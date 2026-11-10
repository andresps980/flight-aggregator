package com.andresps.flights.ingestion.domain;

/** Why a partition (or its last attempt) failed. */
public enum FailureKind {
    /** No first byte, a stall between body bytes, or the attempt deadline exceeded. */
    TIMEOUT,
    /** HTTP 5xx. */
    SERVER_ERROR,
    /** HTTP 4xx: our request was rejected; never retried. */
    CLIENT_ERROR,
    /** Connection refused or reset, or another I/O failure. */
    IO_ERROR,
    /** The body is not a well-formed, complete document of the provider's format. */
    MALFORMED_DOCUMENT,
    /** The provider's circuit breaker is open: no request was made. */
    CIRCUIT_OPEN,
    /** The run deadline expired before the partition finished. */
    RUN_TIMEOUT,
    /**
     * The partition holds a price in a currency other than EUR and the run has no exchange rates (the FX API failed,
     * spec 008). Not the provider's fault: never retried, ignored by its circuit breaker.
     */
    EXCHANGE_RATES_UNAVAILABLE,
    /** A bug in an adapter (an exception it should never throw). */
    UNEXPECTED_ERROR
}
