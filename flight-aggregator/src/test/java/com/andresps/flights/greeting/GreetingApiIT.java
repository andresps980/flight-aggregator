package com.andresps.flights.greeting;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/**
 * Integration test: boots the full application on a random port and exercises the HTTP API end to
 * end (controller -> use case -> configuration-backed adapter).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.greeting.template=Hi, %s!")
class GreetingApiIT {

    @Value("${local.server.port}")
    private int port;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
    }

    @Test
    void greetsUsingConfiguredTemplate() {
        var response = client.get()
                .uri("/api/v1/greetings?name={name}", "Ana")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"message\":\"Hi, Ana!\"");
    }

    @Test
    void rejectsInvalidNameWithProblemDetail() {
        HttpStatusCode status = client.get()
                .uri("/api/v1/greetings?name={name}", "a".repeat(51))
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void exposesHealthEndpoint() {
        var response = client.get().uri("/actuator/health").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("UP");
    }
}
