package com.andresps.flights.search.infrastructure.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.support.MutableClock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResilientCacheStoreTest {

    private final InMemoryCacheStore redis = new InMemoryCacheStore();
    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CircuitBreaker breaker = ResilientCacheStore.circuitBreaker(clock);
    private final ResilientCacheStore store = new ResilientCacheStore(redis, breaker, new CacheMetrics(registry));

    private double count(String result) {
        return registry.counter(CacheMetrics.NAME, "result", result).count();
    }

    @Test
    @DisplayName("AC-18: with Redis healthy, values pass through")
    void passesThrough() {
        store.put("k", "v", Duration.ofMinutes(10));

        assertThat(store.get("k")).contains("v");
        assertThat(redis.ttls.get("k")).isEqualTo(Duration.ofMinutes(10));
        assertThat(count("error")).isZero();
    }

    @Test
    @DisplayName("AC-21: a Redis error reads as a miss and a failed write is skipped; both are counted as errors")
    void errorsAreSwallowed() {
        redis.failing = true;

        assertThat(store.get("k")).isEmpty();
        store.put("k", "v", Duration.ofMinutes(10));

        assertThat(count("error")).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-21 / ADR-0022: after repeated failures the breaker skips Redis for 30 s, then probes it again")
    void breakerSkipsRedisThenRecovers() {
        redis.failing = true;
        for (int i = 0; i < 10 && breaker.getState() == CircuitBreaker.State.CLOSED; i++) {
            store.get("k");
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsWhenOpened = redis.gets.get();

        assertThat(store.get("k")).isEmpty();
        store.put("k", "v", Duration.ofMinutes(10));
        assertThat(redis.gets.get()).isEqualTo(callsWhenOpened);
        assertThat(redis.puts.get()).isZero();
        assertThat(count("bypassed")).isEqualTo(2);

        redis.failing = false;
        clock.advance(Duration.ofSeconds(29));
        store.get("k");
        assertThat(redis.gets.get()).isEqualTo(callsWhenOpened);

        clock.advance(Duration.ofSeconds(1).plusMillis(1));
        store.put("k", "v", Duration.ofMinutes(10));
        store.get("k");
        assertThat(store.get("k")).contains("v");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("AC-21: occasional errors among successes do not open the breaker")
    void toleratesOccasionalErrors() {
        for (int i = 0; i < 50; i++) {
            redis.failing = i % 5 == 0;
            store.get("k");
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
