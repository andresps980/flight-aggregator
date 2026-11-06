package com.andresps.flights.ingestion.domain;

/** What started an ingestion run (ADR-0006). */
public enum IngestionTrigger {
    SCHEDULED,
    MANUAL
}
