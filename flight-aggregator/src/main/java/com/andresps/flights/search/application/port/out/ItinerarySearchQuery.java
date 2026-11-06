package com.andresps.flights.search.application.port.out;

import com.andresps.flights.search.domain.PagePosition;
import com.andresps.flights.search.domain.SearchCriteria;
import com.andresps.flights.search.domain.SearchSlice;
import java.time.Instant;

/**
 * Reads one page of the read model. Contract: only itineraries departing strictly after {@code now} that match every
 * filter of {@code criteria}, ordered by final price then id, strictly after {@code after} ({@code null}: from the
 * start), at most {@code limit} items; {@link SearchSlice#next()} is the position of the last item when more rows
 * follow, else {@code null}. A slice served later (e.g. from a cache) may hold fewer items but the same {@code next}.
 */
public interface ItinerarySearchQuery {

    SearchSlice find(SearchCriteria criteria, PagePosition after, int limit, Instant now);
}
