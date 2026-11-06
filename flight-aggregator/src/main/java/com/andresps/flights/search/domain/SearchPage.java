package com.andresps.flights.search.domain;

import java.util.List;

/** A page of results: {@code nextCursor} is null exactly when {@code hasMore} is false. */
public record SearchPage(List<ItineraryView> items, String nextCursor, boolean hasMore) {

    public SearchPage {
        items = List.copyOf(items);
    }
}
