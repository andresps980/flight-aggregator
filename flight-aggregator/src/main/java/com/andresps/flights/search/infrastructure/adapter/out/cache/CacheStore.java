package com.andresps.flights.search.infrastructure.adapter.out.cache;

import java.time.Duration;
import java.util.Optional;

/** A key-value cache of strings with a TTL; Redis in production, a map in unit tests. */
public interface CacheStore {

    Optional<String> get(String key);

    void put(String key, String value, Duration ttl);
}
