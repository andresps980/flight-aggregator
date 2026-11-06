package com.andresps.flights.search.infrastructure.adapter.out.persistence;

import com.andresps.flights.search.application.port.out.CatalogQuery;
import com.andresps.flights.search.domain.Catalog;
import com.andresps.flights.search.domain.CatalogDate;
import com.andresps.flights.search.domain.Route;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The catalog from PostgreSQL: one grouped pass over the future rows for routes and carriers (a few thousand groups at
 * 1M rows), and the days that still have future flights with their freshness. Cached per data version (ADR-0022), so
 * it runs once per ingestion, not per request.
 */
public final class PostgresCatalogQuery implements CatalogQuery {

    private final JdbcClient jdbc;

    public PostgresCatalogQuery(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public Catalog find(Instant now) {
        OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        SortedMap<String, SortedSet<String>> routes = new TreeMap<>();
        SortedSet<String> destinations = new TreeSet<>();
        SortedSet<String> carriers = new TreeSet<>();
        jdbc.sql("""
                        SELECT origin, destination, airline FROM itinerary
                        WHERE departure_at > ? GROUP BY origin, destination, airline""").param(at).query(rs -> {
            String origin = rs.getString("origin");
            String destination = rs.getString("destination");
            routes.computeIfAbsent(origin, o -> new TreeSet<>()).add(destination);
            destinations.add(destination);
            carriers.add(rs.getString("airline"));
        });
        List<CatalogDate> dates = jdbc.sql("""
                        SELECT s.departure_date, s.complete, s.published_at, s.missing_providers, s.stale_since
                        FROM day_snapshot s
                        WHERE EXISTS (SELECT 1 FROM itinerary i
                                      WHERE i.departure_date = s.departure_date AND i.departure_at > ?)
                        ORDER BY s.departure_date""")
                .param(at)
                .query((rs, n) ->
                        new CatalogDate(rs.getObject("departure_date", LocalDate.class), ReadModelRows.freshness(rs)))
                .list();
        List<Route> routeList = new ArrayList<>();
        for (Map.Entry<String, SortedSet<String>> route : routes.entrySet()) {
            routeList.add(new Route(route.getKey(), List.copyOf(route.getValue())));
        }
        return new Catalog(
                List.copyOf(routes.keySet()), List.copyOf(destinations), List.copyOf(carriers), dates, routeList);
    }
}
