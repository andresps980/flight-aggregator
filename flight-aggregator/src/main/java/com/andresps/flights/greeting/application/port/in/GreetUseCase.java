package com.andresps.flights.greeting.application.port.in;

import com.andresps.flights.greeting.domain.model.Greeting;
import com.andresps.flights.greeting.domain.model.PersonName;

/** Inbound port: what the application offers to driving adapters (REST, CLI, messaging...). */
public interface GreetUseCase {

    Greeting greet(PersonName name);
}
