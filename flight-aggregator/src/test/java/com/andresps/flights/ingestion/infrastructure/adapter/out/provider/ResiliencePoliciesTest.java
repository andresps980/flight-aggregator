package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.ingestion.domain.FailureKind;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import java.time.Duration;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Resilience4j policies built from {@link ResilienceSettings} (ADR-0014), exercised without HTTP. */
class ResiliencePoliciesTest {

    private static final ResilienceSettings DEFAULTS = ResilienceSettings.defaults();

    /** Defaults with no backoff, so retry tests don't sleep. */
    private static final ResilienceSettings NO_BACKOFF = new ResilienceSettings(
            DEFAULTS.connectTimeout(),
            DEFAULTS.readTimeout(),
            DEFAULTS.attemptTimeout(),
            new RetrySettings(3, Duration.ofMillis(1), 1.0, 0.0),
            DEFAULTS.circuitBreaker());

    @Test
    @DisplayName("AC-23: the defaults are the values justified in the spec")
    void defaults() {
        assertThat(DEFAULTS.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(DEFAULTS.readTimeout()).isEqualTo(Duration.ofSeconds(6));
        assertThat(DEFAULTS.attemptTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(DEFAULTS.retry()).isEqualTo(new RetrySettings(3, Duration.ofMillis(500), 2.0, 0.5));
        assertThat(DEFAULTS.circuitBreaker())
                .isEqualTo(new CircuitBreakerSettings(10, 10, 80f, Duration.ofSeconds(30), 2));
    }

    @Test
    @DisplayName("AC-14: backoff is exponential (500 ms, 1 s) with ±50 % jitter")
    void exponentialBackoffWithJitter() {
        IntervalFunction backoff = ResiliencePolicies.backoff(DEFAULTS.retry());

        var first =
                IntStream.range(0, 200).mapToLong(i -> backoff.apply(1)).boxed().toList();
        var second =
                IntStream.range(0, 200).mapToLong(i -> backoff.apply(2)).boxed().toList();

        assertThat(first).allSatisfy(ms -> assertThat(ms).isBetween(250L, 750L));
        assertThat(second).allSatisfy(ms -> assertThat(ms).isBetween(500L, 1500L));
        assertThat(first.stream().distinct().count())
                .as("jitter spreads retries")
                .isGreaterThan(10);
    }

    @ParameterizedTest
    @EnumSource(
            value = FailureKind.class,
            names = {"SERVER_ERROR", "TIMEOUT", "IO_ERROR", "MALFORMED_DOCUMENT"})
    @DisplayName("AC-14: 5xx, timeouts, I/O errors and malformed documents are retried up to max-attempts")
    void retriesRetryableFailures(FailureKind kind) {
        Retry retry = ResiliencePolicies.retry("p", NO_BACKOFF.retry());
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.executeSupplier(() -> {
                    calls.incrementAndGet();
                    throw new AttemptFailedException(kind, "boom", null);
                }))
                .isInstanceOf(AttemptFailedException.class);
        assertThat(calls).hasValue(3);
    }

    @Test
    @DisplayName("AC-14: a 4xx is not retried")
    void doesNotRetryClientErrors() {
        Retry retry = ResiliencePolicies.retry("p", NO_BACKOFF.retry());
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.executeSupplier(() -> {
                    calls.incrementAndGet();
                    throw new AttemptFailedException(FailureKind.CLIENT_ERROR, "404", null);
                }))
                .isInstanceOf(AttemptFailedException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("AC-16: an open breaker is not retried")
    void doesNotRetryOpenBreaker() {
        CircuitBreaker breaker = ResiliencePolicies.circuitBreaker("p", DEFAULTS.circuitBreaker());
        breaker.transitionToOpenState();
        Retry retry = ResiliencePolicies.retry("p", NO_BACKOFF.retry());
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.executeSupplier(() -> breaker.executeSupplier(calls::incrementAndGet)))
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName(
            "AC-17: with the default thresholds, 10 % random errors (fixed seed, 10,000 calls) never open the breaker")
    void staysClosedUnderNormalErrorRate() {
        CircuitBreaker breaker = ResiliencePolicies.circuitBreaker("mock-alpha", DEFAULTS.circuitBreaker());
        AtomicInteger openings = countOpenings(breaker);
        SplittableRandom random = new SplittableRandom(42);
        int failures = 0;

        for (int i = 0; i < 10_000; i++) {
            boolean fail = random.nextDouble() < 0.10;
            failures += fail ? 1 : 0;
            call(breaker, fail ? FailureKind.SERVER_ERROR : null);
        }

        assertThat(failures).as("the seed really produces ~10 %% errors").isBetween(900, 1100);
        assertThat(openings).hasValue(0);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("AC-16/AC-17: sustained failure opens it: 8 of the last 10 attempts failed")
    void opensOnSustainedFailure() {
        CircuitBreaker breaker = ResiliencePolicies.circuitBreaker("p", DEFAULTS.circuitBreaker());

        call(breaker, null);
        call(breaker, null);
        IntStream.range(0, 8).forEach(i -> call(breaker, FailureKind.SERVER_ERROR));

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> breaker.executeSupplier(() -> "x")).isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    @DisplayName("AC-17: 7 failures out of 10 is below the 80 % threshold and keeps it closed")
    void staysClosedBelowThreshold() {
        CircuitBreaker breaker = ResiliencePolicies.circuitBreaker("p", DEFAULTS.circuitBreaker());

        IntStream.range(0, 3).forEach(i -> call(breaker, null));
        IntStream.range(0, 7).forEach(i -> call(breaker, FailureKind.TIMEOUT));

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("AC-16: an outage needs minimum-calls attempts before opening (no decision on 9 failures)")
    void needsMinimumCalls() {
        CircuitBreaker breaker = ResiliencePolicies.circuitBreaker("p", DEFAULTS.circuitBreaker());

        IntStream.range(0, 9).forEach(i -> call(breaker, FailureKind.SERVER_ERROR));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        call(breaker, FailureKind.SERVER_ERROR);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("AC-16: 4xx responses are not breaker failures")
    void ignoresClientErrors() {
        CircuitBreaker breaker = ResiliencePolicies.circuitBreaker("p", DEFAULTS.circuitBreaker());

        IntStream.range(0, 20).forEach(i -> call(breaker, FailureKind.CLIENT_ERROR));

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("AC-16: with the default 30 s open wait (well below the 15 min interval), half-open probes close it")
    void halfOpenRecoveryWithDefaultOpenWait() {
        MutableClock clock = new MutableClock();
        CircuitBreaker breaker = ResiliencePolicies.circuitBreaker("p", DEFAULTS.circuitBreaker(), clock);
        IntStream.range(0, 10).forEach(i -> call(breaker, FailureKind.SERVER_ERROR));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        clock.advance(Duration.ofSeconds(29));
        assertThatThrownBy(() -> breaker.executeSupplier(() -> "x")).isInstanceOf(CallNotPermittedException.class);

        clock.advance(Duration.ofSeconds(1).plusMillis(1)); // just past the 30 s open wait
        call(breaker, null);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        call(breaker, null);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("AC-16: a failed half-open probe reopens the breaker for another open wait")
    void failedProbeReopens() {
        MutableClock clock = new MutableClock();
        CircuitBreaker breaker = ResiliencePolicies.circuitBreaker("p", DEFAULTS.circuitBreaker(), clock);
        IntStream.range(0, 10).forEach(i -> call(breaker, FailureKind.SERVER_ERROR));
        clock.advance(Duration.ofSeconds(30).plusMillis(1));

        call(breaker, FailureKind.SERVER_ERROR);
        call(breaker, FailureKind.SERVER_ERROR);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /** A clock the test moves by hand, so the real open wait is used without sleeping. */
    private static final class MutableClock extends java.time.Clock {

        private java.time.Instant now = java.time.Instant.parse("2026-11-05T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }

    private static void call(CircuitBreaker breaker, FailureKind failure) {
        try {
            breaker.executeSupplier(() -> {
                if (failure != null) {
                    throw new AttemptFailedException(failure, failure.name(), null);
                }
                return "ok";
            });
        } catch (AttemptFailedException expected) {
            // the outcome is what the breaker records
        }
    }

    private static AtomicInteger countOpenings(CircuitBreaker breaker) {
        AtomicInteger openings = new AtomicInteger();
        breaker.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                openings.incrementAndGet();
            }
        });
        return openings;
    }
}
