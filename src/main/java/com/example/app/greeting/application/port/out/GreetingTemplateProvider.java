package com.example.app.greeting.application.port.out;

/** Outbound port: what the application needs from the outside world. */
public interface GreetingTemplateProvider {

    /**
     * @return a {@link String#format} template with exactly one {@code %s} placeholder for the name
     */
    String template();
}
