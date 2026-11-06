package com.andresps.flights.search.infrastructure.adapter.out.cache;

import com.andresps.flights.search.domain.PagePosition;
import com.andresps.flights.search.domain.SearchCriteria;

/**
 * Readable, versioned cache keys (ADR-0022): a new data version simply stops hitting old keys, which expire by TTL.
 * The {@code v1} segment is bumped whenever the cached value format changes.
 */
public final class SearchCacheKeys {

    private SearchCacheKeys() {}

    /** {@code search:v1:<version>:<normalized filters>:l=<limit>:<price/id after, or "first">}. */
    public static String search(long version, SearchCriteria criteria, PagePosition after, int limit) {
        String position = after == null ? "first" : after.finalPrice().toPlainString() + "/" + after.id();
        return "search:v1:" + version + ":" + criteria.canonical() + ":l=" + limit + ":" + position;
    }

    public static String catalog(long version) {
        return "catalog:v1:" + version;
    }
}
