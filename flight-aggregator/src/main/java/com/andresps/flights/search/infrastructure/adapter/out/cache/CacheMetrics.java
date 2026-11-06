package com.andresps.flights.search.infrastructure.adapter.out.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Counter {@value #NAME} tagged {@code result}: {@code hit} / {@code miss} per lookup, {@code error} per failed Redis
 * call, {@code bypassed} per call skipped while the breaker is open (spec 005, ADR-0022).
 */
public final class CacheMetrics {

    public static final String NAME = "search.cache";

    private final Counter hit;
    private final Counter miss;
    private final Counter error;
    private final Counter bypassed;

    public CacheMetrics(MeterRegistry registry) {
        this.hit = counter(registry, "hit");
        this.miss = counter(registry, "miss");
        this.error = counter(registry, "error");
        this.bypassed = counter(registry, "bypassed");
    }

    private static Counter counter(MeterRegistry registry, String result) {
        return Counter.builder(NAME)
                .description("Search cache lookups and Redis failures")
                .tag("result", result)
                .register(registry);
    }

    void hit() {
        hit.increment();
    }

    void miss() {
        miss.increment();
    }

    void error() {
        error.increment();
    }

    void bypassed() {
        bypassed.increment();
    }
}
