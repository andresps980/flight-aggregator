package com.andresps.flights.search.application.port.out;

import com.andresps.flights.search.domain.Catalog;
import java.time.Instant;

/** Reads the catalog over the itineraries departing strictly after {@code now}. */
public interface CatalogQuery {

    Catalog find(Instant now);
}
