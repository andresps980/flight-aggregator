package com.andresps.flights.search.domain;

import java.util.List;

/** What can be searched right now, for the dashboard's dropdowns (spec 005 AC-17). Every list is sorted. */
public record Catalog(
        List<String> origins,
        List<String> destinations,
        List<String> carriers,
        List<CatalogDate> dates,
        List<Route> routes) {

    public Catalog {
        origins = List.copyOf(origins);
        destinations = List.copyOf(destinations);
        carriers = List.copyOf(carriers);
        dates = List.copyOf(dates);
        routes = List.copyOf(routes);
    }
}
