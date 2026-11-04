package com.example.app.greeting.application.port.in;

import com.example.app.greeting.domain.model.Greeting;
import com.example.app.greeting.domain.model.PersonName;

/** Inbound port: what the application offers to driving adapters (REST, CLI, messaging...). */
public interface GreetUseCase {

    Greeting greet(PersonName name);
}
