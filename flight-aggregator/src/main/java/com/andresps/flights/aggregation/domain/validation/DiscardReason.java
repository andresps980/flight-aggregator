package com.andresps.flights.aggregation.domain.validation;

/**
 * Why a well-formed itinerary cannot be sold (dead leg / invalid connection, ADR-0010). Declared in the default
 * evaluation order: structural reasons first, then timing, then time-relative.
 */
public enum DiscardReason {
    MIXED_AIRLINES,
    ARRIVAL_NOT_AFTER_DEPARTURE,
    SEGMENTS_NOT_CHAINED,
    REVISITED_AIRPORT,
    CONNECTION_TOO_SHORT,
    LAYOVER_TOO_LONG,
    ALREADY_DEPARTED
}
