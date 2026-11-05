package com.andresps.gdsmock.chaos.infrastructure.adapter.in.web;

import com.andresps.gdsmock.shared.domain.ProviderId;

/** A forced outage switched on through the admin API (spec 002, AC-17); mapped to {@code 503}. */
public class ProviderOutageException extends RuntimeException {

    public ProviderOutageException(ProviderId provider) {
        super("Provider " + provider + " is in a forced outage");
    }
}
