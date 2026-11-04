package com.andresps.flights.greeting.domain.model;

import java.util.Objects;

public record Greeting(String message) {

    public Greeting {
        Objects.requireNonNull(message, "message must not be null");
    }
}
