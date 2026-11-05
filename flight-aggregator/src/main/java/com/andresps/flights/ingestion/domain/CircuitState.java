package com.andresps.flights.ingestion.domain;

/** State of a provider's circuit breaker, as reported in the ingestion report. */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
