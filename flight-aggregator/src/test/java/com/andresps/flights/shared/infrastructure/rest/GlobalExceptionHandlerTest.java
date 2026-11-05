package com.andresps.flights.shared.infrastructure.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.shared.domain.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class GlobalExceptionHandlerTest {

    private static final class SampleValidationException extends DomainValidationException {
        SampleValidationException(String message) {
            super(message);
        }
    }

    @Test
    void mapsDomainValidationErrorsToBadRequestProblemDetail() {
        ProblemDetail problem = new GlobalExceptionHandler()
                .handleDomainValidation(new SampleValidationException("Airport code invalid"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Invalid request");
        assertThat(problem.getDetail()).isEqualTo("Airport code invalid");
    }
}
