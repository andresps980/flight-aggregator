package com.andresps.flights.search.infrastructure.adapter.in.web;

import com.andresps.flights.search.domain.Catalog;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "What can be searched right now")
public record CatalogResponse(
        List<String> origins,
        List<String> destinations,
        List<String> carriers,
        List<CatalogDateResponse> dates,

        @Schema(description = "Each origin with the destinations it has flights to")
        List<RouteResponse> routes) {

    public record CatalogDateResponse(LocalDate date, FreshnessResponse freshness) {}

    public record RouteResponse(String origin, List<String> destinations) {}

    static CatalogResponse from(Catalog catalog) {
        return new CatalogResponse(
                catalog.origins(),
                catalog.destinations(),
                catalog.carriers(),
                catalog.dates().stream()
                        .map(d -> new CatalogDateResponse(d.date(), FreshnessResponse.from(d.freshness())))
                        .toList(),
                catalog.routes().stream()
                        .map(r -> new RouteResponse(r.origin(), r.destinations()))
                        .toList());
    }
}
