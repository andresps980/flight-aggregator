package com.andresps.flights.search.infrastructure.adapter.out.cache;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes the cache optional (spec 005 AC-21, ADR-0022): a failed or timed-out Redis call reads as a miss or a skipped
 * write, and a circuit breaker skips Redis for 30 s after repeated failures, so a hung Redis does not add its timeout
 * to every request. Breaker transitions are logged (WARN when it opens); individual errors only at DEBUG.
 */
public final class ResilientCacheStore implements CacheStore {

    private static final Logger LOG = LoggerFactory.getLogger(ResilientCacheStore.class);
    private static final int OPEN_SECONDS = 30;

    private final CacheStore delegate;
    private final CircuitBreaker breaker;
    private final CacheMetrics metrics;

    public ResilientCacheStore(CacheStore delegate, CircuitBreaker breaker, CacheMetrics metrics) {
        this.delegate = delegate;
        this.breaker = breaker;
        this.metrics = metrics;
        breaker.getEventPublisher().onStateTransition(event -> {
            switch (event.getStateTransition().getToState()) {
                case OPEN ->
                    LOG.warn(
                            "Search cache unavailable: Redis skipped for {} s, searches read PostgreSQL", OPEN_SECONDS);
                case CLOSED -> LOG.info("Search cache available again");
                default -> LOG.debug("Search cache breaker {}", event.getStateTransition());
            }
        });
    }

    /** 10-call window, opens at ≥ 50 % failures (after 5 calls), 30 s open, 2 probe calls; time from {@code clock}. */
    public static CircuitBreaker circuitBreaker(Clock clock) {
        return CircuitBreaker.of(
                "search-cache",
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(OPEN_SECONDS))
                        .permittedNumberOfCallsInHalfOpenState(2)
                        .clock(clock)
                        .build());
    }

    @Override
    public Optional<String> get(String key) {
        return guarded(() -> delegate.get(key)).flatMap(value -> value);
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        guarded(() -> {
            delegate.put(key, value, ttl);
            return Boolean.TRUE;
        });
    }

    private <T> Optional<T> guarded(Supplier<T> call) {
        if (!breaker.tryAcquirePermission()) {
            metrics.bypassed();
            return Optional.empty();
        }
        long start = breaker.getCurrentTimestamp();
        try {
            T result = call.get();
            breaker.onSuccess(breaker.getCurrentTimestamp() - start, breaker.getTimestampUnit());
            return Optional.of(result);
        } catch (RuntimeException e) {
            breaker.onError(breaker.getCurrentTimestamp() - start, breaker.getTimestampUnit(), e);
            metrics.error();
            LOG.debug("Search cache call failed: {}", e.toString());
            return Optional.empty();
        }
    }
}
