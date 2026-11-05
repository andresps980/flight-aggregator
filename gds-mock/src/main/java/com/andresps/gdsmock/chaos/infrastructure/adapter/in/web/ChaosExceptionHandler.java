package com.andresps.gdsmock.chaos.infrastructure.adapter.in.web;

import com.andresps.gdsmock.shared.infrastructure.rest.GlobalExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps injected failures to Problem Details: {@code 500} for random errors, {@code 503} for forced outages. */
@RestControllerAdvice
public class ChaosExceptionHandler {

    @ExceptionHandler(InjectedProviderErrorException.class)
    ResponseEntity<ProblemDetail> handleInjectedError(InjectedProviderErrorException ex) {
        return GlobalExceptionHandler.problem(HttpStatus.INTERNAL_SERVER_ERROR, "Provider error", ex.getMessage());
    }

    @ExceptionHandler(ProviderOutageException.class)
    ResponseEntity<ProblemDetail> handleOutage(ProviderOutageException ex) {
        return GlobalExceptionHandler.problem(HttpStatus.SERVICE_UNAVAILABLE, "Provider unavailable", ex.getMessage());
    }
}
