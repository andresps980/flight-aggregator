package com.andresps.flights.greeting.infrastructure.adapter.in.rest;

import com.andresps.flights.greeting.application.port.in.GreetUseCase;
import com.andresps.flights.greeting.domain.model.PersonName;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/greetings")
public class GreetingController {

    private final GreetUseCase greetUseCase;

    public GreetingController(GreetUseCase greetUseCase) {
        this.greetUseCase = greetUseCase;
    }

    @GetMapping
    public GreetingResponse greet(@RequestParam(defaultValue = "World") String name) {
        return GreetingResponse.from(greetUseCase.greet(new PersonName(name)));
    }
}
