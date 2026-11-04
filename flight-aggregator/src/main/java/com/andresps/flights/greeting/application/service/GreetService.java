package com.andresps.flights.greeting.application.service;

import com.andresps.flights.greeting.application.port.in.GreetUseCase;
import com.andresps.flights.greeting.application.port.out.GreetingTemplateProvider;
import com.andresps.flights.greeting.domain.model.Greeting;
import com.andresps.flights.greeting.domain.model.PersonName;
import java.util.Objects;

/**
 * Use case implementation. Framework-free: it is wired as a bean in the infrastructure layer
 * (see {@code GreetingConfiguration}), so it can be unit-tested without Spring.
 */
public class GreetService implements GreetUseCase {

    private final GreetingTemplateProvider templateProvider;

    public GreetService(GreetingTemplateProvider templateProvider) {
        this.templateProvider = Objects.requireNonNull(templateProvider);
    }

    @Override
    public Greeting greet(PersonName name) {
        return new Greeting(templateProvider.template().formatted(name.value()));
    }
}
