package com.andresps.flights.search.infrastructure.adapter.in.web;

import com.andresps.flights.search.domain.SearchPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "A page of search results")
public record ItineraryPageResponse(
        @Schema(description = "Matching itineraries, cheapest first")
        List<ItineraryResponse> items,

        @Schema(
                description = "Cursor for the next page, null when hasMore is false. A page served from the "
                        + "cache omits itineraries that departed after it was cached, so it may be shorter than "
                        + "limit, or even empty, while hasMore is true: keep paging until hasMore is false.",
                nullable = true)
        String nextCursor,

        @Schema(description = "Whether more results follow") boolean hasMore) {

    static ItineraryPageResponse from(SearchPage page) {
        return new ItineraryPageResponse(
                page.items().stream().map(ItineraryResponse::from).toList(), page.nextCursor(), page.hasMore());
    }
}
