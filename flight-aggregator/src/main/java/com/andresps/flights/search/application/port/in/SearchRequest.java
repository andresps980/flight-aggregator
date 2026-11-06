package com.andresps.flights.search.application.port.in;

/**
 * The raw query parameters of a search, as received (spec 005): any of them may be {@code null} or blank. Validation
 * belongs to the use case, so every client gets the same rules and messages.
 */
public record SearchRequest(
        String origin, String destination, String date, String maxPrice, String carrier, String limit, String cursor) {}
