package com.andresps.flights.search.infrastructure.adapter.out.cache;

import com.andresps.flights.search.application.port.out.DataVersionQuery;
import com.andresps.flights.search.application.port.out.ItinerarySearchQuery;
import com.andresps.flights.search.domain.PagePosition;
import com.andresps.flights.search.domain.SearchCriteria;
import com.andresps.flights.search.domain.SearchSlice;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache-aside decorator of the page query (ADR-0022): keyed by data version, filters, limit and position, never by
 * time. On a hit, items departed since the page was cached are dropped and the next position is kept (AC-20).
 */
public final class CachingItinerarySearchQuery implements ItinerarySearchQuery {

    private static final Logger LOG = LoggerFactory.getLogger(CachingItinerarySearchQuery.class);

    private final ItinerarySearchQuery delegate;
    private final DataVersionQuery versions;
    private final CacheStore cache;
    private final SearchCacheCodec codec;
    private final CacheMetrics metrics;
    private final Duration ttl;

    public CachingItinerarySearchQuery(
            ItinerarySearchQuery delegate,
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
    public SearchSlice find(SearchCriteria criteria, PagePosition after, int limit, Instant now) {
        // read before the data: a value is never older than its key's version (ADR-0022)
        String key = SearchCacheKeys.search(versions.current(), criteria, after, limit);
        Optional<SearchSlice> cached = cache.get(key).flatMap(this::decode);
        if (cached.isPresent()) {
            metrics.hit();
            return cached.get().withoutDepartedAt(now);
        }
        metrics.miss();
        SearchSlice slice = delegate.find(criteria, after, limit, now);
        cache.put(key, codec.write(slice), ttl);
        return slice;
    }

    private Optional<SearchSlice> decode(String value) {
        try {
            return Optional.of(codec.readSlice(value));
        } catch (RuntimeException e) {
            LOG.debug("Unreadable cached search page, read again: {}", e.toString());
            return Optional.empty();
        }
    }
}
