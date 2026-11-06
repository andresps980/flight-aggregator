package com.andresps.flights.search.infrastructure.adapter.in.web;

import com.andresps.flights.search.application.port.in.GetCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/catalog}: what can be searched right now, for the dashboard's dropdowns (spec 005 AC-17). */
@RestController
@Tag(name = "Search")
public class CatalogController {

    private final GetCatalog getCatalog;

    public CatalogController(GetCatalog getCatalog) {
        this.getCatalog = getCatalog;
    }

    @GetMapping(value = "/api/v1/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Search catalog",
            description = "Sorted origins, destinations, carriers, departure dates (with freshness) and routes over "
                    + "the itineraries still to depart.")
    public CatalogResponse catalog() {
        return CatalogResponse.from(getCatalog.catalog());
    }
}
