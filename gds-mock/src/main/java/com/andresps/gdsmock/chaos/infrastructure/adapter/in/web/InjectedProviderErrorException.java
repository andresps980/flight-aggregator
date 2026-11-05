package com.andresps.gdsmock.chaos.infrastructure.adapter.in.web;

import com.andresps.gdsmock.shared.domain.ProviderId;

/** A simulated provider failure (spec 002, AC-16); mapped to {@code 500}. */
public class InjectedProviderErrorException extends RuntimeException {

    public InjectedProviderErrorException(ProviderId provider) {
        super("Simulated internal error of provider " + provider);
    }

    public InjectedProviderErrorException(ProviderId provider, Throwable cause) {
        super("Request to provider " + provider + " was interrupted", cause);
    }
}
