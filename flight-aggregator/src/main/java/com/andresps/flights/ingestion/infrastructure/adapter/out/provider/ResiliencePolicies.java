package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Clock;
import java.util.function.Predicate;

/** Resilience4j policies (core modules only, no Spring integration) built from our settings (ADR-0014). */
public final class ResiliencePolicies {

    /** Provider failures: retried and recorded by the breaker. Anything else (4xx, cancellation) is neither. */
    private static final Predicate<Throwable> PROVIDER_FAILURE =
            e -> e instanceof AttemptFailedException failure && failure.retryable();

    private static final Predicate<Throwable> NOT_A_PROVIDER_FAILURE =
            e -> e instanceof AttemptFailedException failure && !failure.retryable();

    private ResiliencePolicies() {}

    public static CircuitBreaker circuitBreaker(String name, CircuitBreakerSettings settings) {
        return circuitBreaker(name, settings, Clock.systemUTC());
    }

    /** With an explicit clock for the open wait, so tests can use the real 30 s without sleeping. */
    public static CircuitBreaker circuitBreaker(String name, CircuitBreakerSettings settings, Clock clock) {
        return CircuitBreaker.of(name, circuitBreakerConfig(settings, clock));
    }

    public static CircuitBreakerConfig circuitBreakerConfig(CircuitBreakerSettings settings, Clock clock) {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindow())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.openWait())
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenCalls())
                .recordException(PROVIDER_FAILURE)
                .ignoreException(NOT_A_PROVIDER_FAILURE)
                .clock(clock)
                .build();
    }

    public static Retry retry(String name, RetrySettings settings) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(settings.maxAttempts())
                .intervalFunction(backoff(settings))
                .retryOnException(PROVIDER_FAILURE)
                .failAfterMaxAttempts(false) // rethrow the last attempt's failure as is
                .build();
        return Retry.of(name, config);
    }

    /** Exponential backoff with jitter: {@code initial × multiplier^(attempt-1) × (1 ± jitter)}. */
    public static IntervalFunction backoff(RetrySettings settings) {
        return IntervalFunction.ofExponentialRandomBackoff(
                settings.initialBackoff(), settings.multiplier(), settings.jitter());
    }
}
