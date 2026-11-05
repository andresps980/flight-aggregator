package com.andresps.gdsmock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Mock GDS providers (Mock-Alpha JSON, Mock-Beta XML, Mock-Gamma CSV) with chaos. See specs/002-gds-mock. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GdsMockApplication {

    public static void main(String[] args) {
        SpringApplication.run(GdsMockApplication.class, args);
    }
}
