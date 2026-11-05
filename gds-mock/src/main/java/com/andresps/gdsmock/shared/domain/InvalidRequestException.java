package com.andresps.gdsmock.shared.domain;

/** A request that can't be served as asked (bad parameter); mapped to {@code 400 Bad Request}. */
public abstract class InvalidRequestException extends RuntimeException {

    protected InvalidRequestException(String message) {
        super(message);
    }
}
