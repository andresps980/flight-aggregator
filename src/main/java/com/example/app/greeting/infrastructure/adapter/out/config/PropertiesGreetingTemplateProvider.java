package com.example.app.greeting.infrastructure.adapter.out.config;

import com.example.app.greeting.application.port.out.GreetingTemplateProvider;

/** Outbound adapter backed by application configuration ({@code app.greeting.template}). */
public class PropertiesGreetingTemplateProvider implements GreetingTemplateProvider {

    private final String template;

    public PropertiesGreetingTemplateProvider(GreetingProperties properties) {
        this.template = properties.template();
    }

    @Override
    public String template() {
        return template;
    }
}
