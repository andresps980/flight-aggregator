package com.andresps.flights.search.infrastructure.adapter.in.web;

import com.andresps.flights.search.application.port.in.SearchItineraries;
import com.andresps.flights.search.application.port.in.SearchRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/itineraries} (spec 005). Parameters arrive as strings and are validated by the use case, so every
 * malformed value gets the same {@code 400} ProblemDetail naming the parameter.
 */
@RestController
@Tag(name = "Search", description = "Future itineraries, cheapest first, keyset-paginated")
public class ItinerarySearchController {

    private final SearchItineraries searchItineraries;

    public ItinerarySearchController(SearchItineraries searchItineraries) {
        this.searchItineraries = searchItineraries;
    }

    @GetMapping(value = "/api/v1/itineraries", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Search itineraries",
            description = "Itineraries departing after now that match every given filter, ordered by final price "
                    + "ascending (ties by an internal id). Keep requesting with nextCursor while hasMore is true. "
                    + "No total count is returned.")
    @ApiResponse(responseCode = "200", description = "A page of results")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid parameter, or a cursor that is malformed or was issued for other filters",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ItineraryPageResponse search(
            @Parameter(
                            description = "Origin airport (IATA, 3 letters, case-insensitive)",
                            example = "MAD",
                            schema = @Schema(type = "string", pattern = "^[A-Za-z]{3}$"))
                    @RequestParam(required = false)
                    String origin,
            @Parameter(
                            description = "Final destination airport (IATA, 3 letters, case-insensitive)",
                            example = "JFK",
                            schema = @Schema(type = "string", pattern = "^[A-Za-z]{3}$"))
                    @RequestParam(required = false)
                    String destination,
            @Parameter(
                            description = "UTC day of the first departure; absent: every future day. "
                                    + "From yesterday to today + 365 days",
                            example = "2026-11-08",
                            schema = @Schema(type = "string", format = "date"))
                    @RequestParam(required = false)
                    String date,
            @Parameter(
                            description = "Maximum final price in EUR, inclusive, at most 2 decimals",
                            example = "650",
                            schema = @Schema(type = "number", minimum = "0", maximum = "999999999.99"))
                    @RequestParam(required = false)
                    String maxPrice,
            @Parameter(
                            description = "Airline (IATA, 2 characters A-Z/0-9, case-insensitive)",
                            example = "IB",
                            schema = @Schema(type = "string", pattern = "^[A-Za-z0-9]{2}$"))
                    @RequestParam(required = false)
                    String carrier,
            @Parameter(
                            description = "Items per page",
                            schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
                    @RequestParam(required = false)
                    String limit,
            @Parameter(
                            description = "Opaque nextCursor of the previous page; only valid with the same filters",
                            schema = @Schema(type = "string"))
                    @RequestParam(required = false)
                    String cursor) {
        return ItineraryPageResponse.from(searchItineraries.search(
                new SearchRequest(origin, destination, date, maxPrice, carrier, limit, cursor)));
    }
}
