package com.andresps.gdsmock.inventory.domain.model;

/**
 * Business-invalid itinerary kinds. Names and order mirror spec 001's discard reasons and evaluation order: each
 * injected itinerary breaks its kind as the first failing rule (spec 002, AC-6).
 */
public enum InvalidKind {
    MIXED_AIRLINES,
    ARRIVAL_NOT_AFTER_DEPARTURE,
    SEGMENTS_NOT_CHAINED,
    REVISITED_AIRPORT,
    CONNECTION_TOO_SHORT,
    LAYOVER_TOO_LONG,
    ALREADY_DEPARTED
}
