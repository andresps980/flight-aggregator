package com.andresps.flights.search.application.service;

import com.andresps.flights.search.application.port.in.GetCatalog;
import com.andresps.flights.search.application.port.out.CatalogQuery;
import com.andresps.flights.search.domain.Catalog;
import java.time.Clock;
import java.util.Objects;

/** The catalog over the itineraries still to depart (spec 005 AC-17). */
public final class CatalogService implements GetCatalog {

    private final CatalogQuery query;
    private final Clock clock;

    public CatalogService(CatalogQuery query, Clock clock) {
        this.query = Objects.requireNonNull(query, "query");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Catalog catalog() {
        return query.find(clock.instant());
    }
}
