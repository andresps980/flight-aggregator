package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

import com.andresps.flights.ingestion.domain.MalformedReason;

/**
 * One record can't become an offer. Thrown and caught inside a parser for every malformed record, so it carries no
 * stack trace (about 1 % of records are malformed by design).
 */
public final class MalformedRecordException extends RuntimeException {

    private final MalformedReason reason;

    public MalformedRecordException(MalformedReason reason, String message) {
        super(message, null, false, false);
        this.reason = reason;
    }

    public MalformedReason reason() {
        return reason;
    }
}
