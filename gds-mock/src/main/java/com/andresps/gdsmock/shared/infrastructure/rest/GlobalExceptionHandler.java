package com.andresps.gdsmock.shared.infrastructure.rest;

import com.andresps.gdsmock.shared.domain.InvalidRequestException;
import com.andresps.gdsmock.shared.domain.UnknownProviderException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps request errors to RFC 9457 Problem Details. The content type is set explicitly so the error renders even when
 * the client asked for XML or CSV.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ProblemDetail> handleInvalidRequest(InvalidRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage());
    }

    @ExceptionHandler(UnknownProviderException.class)
    ResponseEntity<ProblemDetail> handleUnknownProvider(UnknownProviderException ex) {
        return problem(HttpStatus.NOT_FOUND, "Unknown provider", ex.getMessage());
    }

    /** Builds a Problem Details response with an explicit {@code application/problem+json} content type. */
    public static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
