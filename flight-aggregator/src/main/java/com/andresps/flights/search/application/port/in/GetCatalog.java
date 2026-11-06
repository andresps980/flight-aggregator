package com.andresps.flights.search.application.port.in;

import com.andresps.flights.search.domain.Catalog;

/** What can be searched right now: origins, destinations, carriers, dates and routes (spec 005 AC-17). */
public interface GetCatalog {

    Catalog catalog();
}
