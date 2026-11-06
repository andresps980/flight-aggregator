package com.andresps.flights.search.domain;

import java.time.Instant;
import java.util.List;

/**
 * What the query port returns for one page: the items and, when more rows follow, the position to continue from — the
 * last row the query read, carried explicitly so that dropping items later (a cached page whose flights departed,
 * ADR-0022) never moves the cursor back.
 */
public record SearchSlice(List<ItineraryView> items, PagePosition next) {

    public SearchSlice {
        items = List.copyOf(items);
    }

    public boolean hasMore() {
        return next != null;
    }

    /** The same slice without the items departed at {@code now}; {@link #next()} is kept. */
    public SearchSlice withoutDepartedAt(Instant now) {
        return new SearchSlice(
                items.stream().filter(item -> !item.departedAt(now)).toList(), next);
    }
}
