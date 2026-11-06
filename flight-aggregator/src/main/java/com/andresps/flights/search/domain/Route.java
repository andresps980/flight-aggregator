package com.andresps.flights.search.domain;

import java.util.List;

/** An origin with the destinations it has flights to (sorted). */
public record Route(String origin, List<String> destinations) {

    public Route {
        destinations = List.copyOf(destinations);
    }
}
