package com.example.app.greeting.infrastructure.adapter.in.rest;

import com.example.app.greeting.domain.model.Greeting;

/** REST DTO. Keeps the HTTP contract independent from the domain model. */
public record GreetingResponse(String message) {

    static GreetingResponse from(Greeting greeting) {
        return new GreetingResponse(greeting.message());
    }
}
