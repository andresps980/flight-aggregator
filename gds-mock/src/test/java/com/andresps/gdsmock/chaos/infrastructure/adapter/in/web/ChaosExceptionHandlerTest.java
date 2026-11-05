package com.andresps.gdsmock.chaos.infrastructure.adapter.in.web;

import static com.andresps.gdsmock.support.WorldFixtures.BETA;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class ChaosExceptionHandlerTest {

    private final ChaosExceptionHandler handler = new ChaosExceptionHandler();

    @Test
    @DisplayName("AC-16: an injected error becomes a 500 Problem Detail")
    void injectedError() {
        var response = handler.handleInjectedError(new InjectedProviderErrorException(BETA));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().getDetail()).contains("mock-beta");
    }

    @Test
    @DisplayName("AC-17: a forced outage becomes a 503 Problem Detail")
    void outage() {
        var response = handler.handleOutage(new ProviderOutageException(BETA));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().getDetail()).contains("mock-beta");
    }
}
