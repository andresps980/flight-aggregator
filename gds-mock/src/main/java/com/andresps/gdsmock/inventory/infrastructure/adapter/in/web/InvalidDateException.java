package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web;

import com.andresps.gdsmock.shared.domain.InvalidRequestException;

/** A {@code date} parameter that is not a valid ISO date. */
public class InvalidDateException extends InvalidRequestException {

    public InvalidDateException(String date) {
        super("Invalid date '%s': expected YYYY-MM-DD".formatted(date));
    }
}
