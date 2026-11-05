package com.andresps.gdsmock.inventory.domain.model;

/** Ways a single provider record is malformed (spec 002, AC-7). */
public enum MalformationKind {
    /** An airport code that is not 3 letters (a digit replaces the middle letter). */
    BAD_AIRPORT_CODE,
    /** A price of zero or below. */
    NON_POSITIVE_PRICE,
    /** A departure time each format renders as text its clients can't parse. */
    UNPARSEABLE_TIME
}
