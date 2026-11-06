package com.andresps.flights.search.infrastructure.adapter.out.cache;

import com.andresps.flights.search.application.port.out.CatalogQuery;
import com.andresps.flights.search.application.port.out.DataVersionQuery;
import com.andresps.flights.search.domain.Catalog;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache-aside decorator of the catalog query, computed once per data version (ADR-0022).
 *
 * <p>The catalog is a grouped scan of every future row (≈ 450 ms at 1M), so it is also memoised in this JVM per
 * version, behind a lock: when a run bumps the version, or while Redis is down, concurrent requests wait for one
 * computation instead of each scanning the table (and exhausting the connection pool shared with ingestion). Days
 * before today (UTC) are dropped from a reused catalog, so it never offers a date that can only return nothing.
 */
public final class CachingCatalogQuery implements CatalogQuery {

    private static final Logger LOG = LoggerFactory.getLogger(CachingCatalogQuery.class);

    private record Versioned(long version, Catalog catalog) {}

    private final CatalogQuery delegate;
    private final DataVersionQuery versions;
    private final CacheStore cache;
    private final SearchCacheCodec codec;
    private final CacheMetrics metrics;
    private final Duration ttl;
    private final ReentrantLock computing = new ReentrantLock();
    private volatile Versioned memo;

    public CachingCatalogQuery(
            CatalogQuery delegate,
            DataVersionQuery versions,
            CacheStore cache,
            SearchCacheCodec codec,
            CacheMetrics metrics,
            Duration ttl) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    @Override
    public Catalog find(Instant now) {
        long version = versions.current();
        Versioned current = memo;
        if (current == null || current.version() != version) {
            computing.lock();
            try {
                current = memo;
                if (current == null || current.version() != version) {
                    current = new Versioned(version, load(version, now));
                    memo = current;
                } else {
                    metrics.hit();
                }
            } finally {
                computing.unlock();
            }
        } else {
            metrics.hit();
        }
        return withoutPastDates(current.catalog(), now);
    }

    /** Redis first, then PostgreSQL; called by one thread at a time. */
    private Catalog load(long version, Instant now) {
        String key = SearchCacheKeys.catalog(version);
        Optional<Catalog> cached = cache.get(key).flatMap(this::decode);
        if (cached.isPresent()) {
            metrics.hit();
            return cached.get();
        }
        metrics.miss();
        Catalog catalog = delegate.find(now);
        cache.put(key, codec.write(catalog), ttl);
        return catalog;
    }

    private static Catalog withoutPastDates(Catalog catalog, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (catalog.dates().stream().noneMatch(d -> d.date().isBefore(today))) {
            return catalog;
        }
        return new Catalog(
                catalog.origins(),
                catalog.destinations(),
                catalog.carriers(),
                catalog.dates().stream().filter(d -> !d.date().isBefore(today)).toList(),
                catalog.routes());
    }

    private Optional<Catalog> decode(String value) {
        try {
            return Optional.of(codec.readCatalog(value));
        } catch (RuntimeException e) {
            LOG.debug("Unreadable cached catalog, read again: {}", e.toString());
            return Optional.empty();
        }
    }
}
