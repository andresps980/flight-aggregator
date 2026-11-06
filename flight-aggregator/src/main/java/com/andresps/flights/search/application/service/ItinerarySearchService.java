package com.andresps.flights.search.application.service;

import com.andresps.flights.search.application.port.in.SearchItineraries;
import com.andresps.flights.search.application.port.in.SearchRequest;
import com.andresps.flights.search.application.port.out.ItinerarySearchQuery;
import com.andresps.flights.search.domain.Cursor;
import com.andresps.flights.search.domain.PagePosition;
import com.andresps.flights.search.domain.PageSize;
import com.andresps.flights.search.domain.SearchCriteria;
import com.andresps.flights.search.domain.SearchPage;
import com.andresps.flights.search.domain.SearchSlice;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Validates the request, checks the cursor against the filters, reads one page through the query port and issues the
 * next cursor (spec 005, ADR-0021). Knows nothing about SQL or Redis.
 */
public final class ItinerarySearchService implements SearchItineraries {

    private final ItinerarySearchQuery query;
    private final Clock clock;

    public ItinerarySearchService(ItinerarySearchQuery query, Clock clock) {
        this.query = Objects.requireNonNull(query, "query");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SearchPage search(SearchRequest request) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        SearchCriteria criteria = SearchCriteria.parse(
                request.origin(), request.destination(), request.date(), request.maxPrice(), request.carrier(), today);
        PageSize size = PageSize.parse(request.limit());
        String fingerprint = criteria.fingerprint();
        PagePosition after = null;
        if (request.cursor() != null && !request.cursor().isBlank()) {
            Cursor cursor = Cursor.decode(request.cursor().strip());
            cursor.requireIssuedFor(fingerprint);
            after = cursor.position();
        }

        SearchSlice slice = query.find(criteria, after, size.value(), now);

        String nextCursor = slice.hasMore() ? new Cursor(slice.next(), fingerprint).encode() : null;
        return new SearchPage(slice.items(), nextCursor, slice.hasMore());
    }
}
