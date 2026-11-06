package com.andresps.flights.search.infrastructure.adapter.out.cache;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** A {@link CacheStore} fake: a map that remembers each entry's TTL, counts calls and can be made to fail. */
final class InMemoryCacheStore implements CacheStore {

    final Map<String, String> values = new ConcurrentHashMap<>();
    final Map<String, Duration> ttls = new ConcurrentHashMap<>();
    final AtomicInteger gets = new AtomicInteger();
    final AtomicInteger puts = new AtomicInteger();
    volatile boolean failing;

    @Override
    public Optional<String> get(String key) {
        gets.incrementAndGet();
        failIfAsked();
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        puts.incrementAndGet();
        failIfAsked();
        values.put(key, value);
        ttls.put(key, ttl);
    }

    private void failIfAsked() {
        if (failing) {
            throw new IllegalStateException("Redis is down (test)");
        }
    }
}
