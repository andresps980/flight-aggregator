package com.andresps.flights.search.domain;

import java.time.Instant;

/** One flight of an itinerary, as stored in the read model. */
public record SegmentView(
        String flightNumber, String origin, String destination, Instant departureAt, Instant arrivalAt) {}
