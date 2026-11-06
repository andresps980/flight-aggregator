package com.andresps.flights.search.infrastructure.adapter.out.cache;

import com.andresps.flights.search.application.port.out.CatalogQuery;
import com.andresps.flights.search.application.port.out.DataVersionQuery;
import com.andresps.flights.search.domain.Catalog;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Cache-aside decorator of the catalog query: computed once per data version (ADR-0022). */
public final class CachingCatalogQuery implements CatalogQuery {

    private static final Logger LOG = LoggerFactory.getLogger(CachingCatalogQuery.class);

    private final CatalogQuery delegate;
    private final DataVersionQuery versions;
    private final CacheStore cache;
    private final SearchCacheCodec codec;
    private final CacheMetrics metrics;
    private final Duration ttl;

    public CachingCatalogQuery(
            CatalogQuery delegate,
            DataVersionQuery versions,
            CacheStore cache,
            SearchCacheCodec codec,
            CacheMetrics metrics,
            Duration ttl) {
        this.delegate = delegate;
        this.versions = versions;
        this.cache = cache;
        this.codec = codec;
        this.metrics = metrics;
        this.ttl = ttl;
    }

    @Override
    public Catalog find(Instant now) {
        String key = SearchCacheKeys.catalog(versions.current());
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

    private Optional<Catalog> decode(String value) {
        try {
            return Optional.of(codec.readCatalog(value));
        } catch (RuntimeException e) {
            LOG.debug("Unreadable cached catalog, read again: {}", e.toString());
            return Optional.empty();
        }
    }
}
