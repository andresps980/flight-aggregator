package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web;

import com.andresps.gdsmock.shared.domain.InvalidRequestException;

/** {@code /fx/latest} only serves EUR-based rates (spec 008 AC-15). */
public class UnsupportedBaseCurrencyException extends InvalidRequestException {

    public UnsupportedBaseCurrencyException(String base) {
        super("Unsupported base '%s': only base=EUR is served".formatted(base));
    }
}
