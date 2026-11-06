package com.andresps.flights.search.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code aggregator.search.*} (spec 005, ADR-0022). */
@ConfigurationProperties("aggregator.search")
public record SearchProperties(@DefaultValue Cache cache) {

    /**
     * @param enabled whether searches go through the Redis cache (off: always PostgreSQL)
     * @param ttl how long an entry lives; a safety net and memory bound, not the invalidation (the data version is)
     */
    public record Cache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10m") Duration ttl) {}
}
