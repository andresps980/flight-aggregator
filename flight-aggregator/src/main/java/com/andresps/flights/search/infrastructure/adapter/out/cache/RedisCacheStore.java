package com.andresps.flights.search.infrastructure.adapter.out.cache;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/** {@code GET} / {@code SET … PX} on Redis. Failures surface as exceptions; {@link ResilientCacheStore} absorbs them. */
public final class RedisCacheStore implements CacheStore {

    private final StringRedisTemplate redis;

    public RedisCacheStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }
}
