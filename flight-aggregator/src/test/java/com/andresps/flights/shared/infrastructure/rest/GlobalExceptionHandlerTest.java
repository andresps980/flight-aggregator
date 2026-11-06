package com.andresps.flights.shared.infrastructure.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.shared.domain.ConflictException;
import com.andresps.flights.shared.domain.DomainValidationException;
import com.andresps.flights.shared.domain.ResourceNotFoundException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
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

    @Test
    @DisplayName("spec 004 AC-23: a missing resource maps to a 404 ProblemDetail")
    void mapsNotFoundToProblemDetail() {
        ProblemDetail problem = new GlobalExceptionHandler()
                .handleNotFound(new ResourceNotFoundException("Ingestion not found", "No ingestion 42"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Ingestion not found");
        assertThat(problem.getDetail()).isEqualTo("No ingestion 42");
    }

    @Test
    @DisplayName("spec 004 AC-21: a conflict maps to a 409 ProblemDetail carrying its extra properties")
    void mapsConflictToProblemDetail() {
        ProblemDetail problem = new GlobalExceptionHandler()
                .handleConflict(new ConflictException(
                        "Ingestion already running", "Ingestion 7 is in progress", Map.of("runningIngestionId", "7")));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Ingestion already running");
        assertThat(problem.getDetail()).isEqualTo("Ingestion 7 is in progress");
        assertThat(problem.getProperties()).containsEntry("runningIngestionId", "7");
    }
}
