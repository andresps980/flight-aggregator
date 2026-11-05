package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

/** The body is not a well-formed, complete document of the provider's format: the whole attempt fails. */
public class MalformedDocumentException extends RuntimeException {

    public MalformedDocumentException(String message) {
        super(message);
    }

    public MalformedDocumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
