package com.andresps.flights.search.infrastructure.adapter.out.cache;

import com.andresps.flights.search.domain.Catalog;
import com.andresps.flights.search.domain.SearchSlice;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cached values as JSON of the search records (prices keep their scale, instants ISO-8601). Unknown properties are
 * ignored, so derived accessors such as {@code hasMore} round-trip; a format change bumps the key prefix
 * ({@link SearchCacheKeys}). Decoding failures throw; the decorators treat them as misses.
 */
public final class SearchCacheCodec {

    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    public String write(SearchSlice slice) {
        return json.writeValueAsString(slice);
    }

    public SearchSlice readSlice(String value) {
        return json.readValue(value, SearchSlice.class);
    }

    public String write(Catalog catalog) {
        return json.writeValueAsString(catalog);
    }

    public Catalog readCatalog(String value) {
        return json.readValue(value, Catalog.class);
    }
}
