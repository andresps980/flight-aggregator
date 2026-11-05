package com.andresps.flights.ingestion.domain;

/**
 * Why a provider record could not become a {@code FlightOffer}. One reason per record: the first failing field in
 * document order. The first three match gds-mock's malformation kinds, so counts reconcile with its stats.
 */
public enum MalformedReason {
    /** An airport code that is present but not 3 letters. */
    BAD_AIRPORT_CODE,
    /** A carrier or flight number that is present but not a valid airline code plus 1-4 digits. */
    BAD_FLIGHT_NUMBER,
    /** A price of zero or below. */
    NON_POSITIVE_PRICE,
    /** A time that is present but can't be parsed in the provider's format. */
    UNPARSEABLE_TIME,
    /** Anything else: missing field, wrong field count, unknown zone, non-EUR or non-numeric price, no segments… */
    INVALID_RECORD
}
