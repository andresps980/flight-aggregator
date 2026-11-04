package com.example.app.greeting.infrastructure.adapter.out.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.greeting")
public record GreetingProperties(
        @NotBlank @Pattern(regexp = "^[^%]*%s[^%]*$", message = "must contain exactly one %s placeholder")
        String template) {}
