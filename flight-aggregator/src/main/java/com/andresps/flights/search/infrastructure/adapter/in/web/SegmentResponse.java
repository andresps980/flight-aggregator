package com.andresps.flights.search.infrastructure.adapter.in.web;

import com.andresps.flights.search.domain.SegmentView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "One flight of the itinerary")
public record SegmentResponse(
        @Schema(example = "IB6251") String flightNumber,
        String origin,
        String destination,
        Instant departureAt,
        Instant arrivalAt) {

    static SegmentResponse from(SegmentView segment) {
        return new SegmentResponse(
                segment.flightNumber(),
                segment.origin(),
                segment.destination(),
                segment.departureAt(),
                segment.arrivalAt());
    }
}
