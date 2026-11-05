package com.andresps.gdsmock.shared.infrastructure.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.gdsmock.shared.domain.InvalidRequestException;
import com.andresps.gdsmock.shared.domain.ProviderId;
import com.andresps.gdsmock.shared.domain.UnknownProviderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static final class SampleInvalidRequest extends InvalidRequestException {
        SampleInvalidRequest(String message) {
            super(message);
        }
    }

    @Test
    @DisplayName("AC-14 (error): invalid requests become 400 Problem Details")
    void invalidRequest() {
        ResponseEntity<ProblemDetail> response = handler.handleInvalidRequest(new SampleInvalidRequest("bad date"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().getTitle()).isEqualTo("Invalid request");
        assertThat(response.getBody().getDetail()).isEqualTo("bad date");
    }

    @Test
    @DisplayName("AC-14, AC-18 (error): unknown providers become 404 Problem Details")
    void unknownProvider() {
        ResponseEntity<ProblemDetail> response =
                handler.handleUnknownProvider(new UnknownProviderException(new ProviderId("mock-delta")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().getTitle()).isEqualTo("Unknown provider");
        assertThat(response.getBody().getDetail()).contains("mock-delta");
    }
}
