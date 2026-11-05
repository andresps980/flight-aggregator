package com.andresps.gdsmock.shared.domain;

/** No provider with the requested id; mapped to {@code 404 Not Found}. */
public class UnknownProviderException extends RuntimeException {

    public UnknownProviderException(ProviderId provider) {
        this(provider.value());
    }

    public UnknownProviderException(String provider) {
        super("Unknown provider '" + provider + "'");
    }
}
