package com.andresps.flights.search.application.port.in;

import com.andresps.flights.search.domain.SearchPage;

/** Searches future itineraries, cheapest first, one keyset page at a time (spec 005). */
public interface SearchItineraries {

    /**
     * @throws com.andresps.flights.search.domain.InvalidSearchRequestException for an invalid parameter or a cursor
     *     that is malformed or was issued for other filters
     */
    SearchPage search(SearchRequest request);
}
