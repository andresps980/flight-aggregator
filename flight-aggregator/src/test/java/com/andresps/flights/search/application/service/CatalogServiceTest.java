package com.andresps.flights.search.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.andresps.flights.search.application.port.out.CatalogQuery;
import com.andresps.flights.search.domain.Catalog;
import com.andresps.flights.search.domain.Route;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CatalogServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    @Test
    @DisplayName("AC-17: the catalog is read over the itineraries still to depart at now")
    void readsAtNow() {
        CatalogQuery query = mock(CatalogQuery.class);
        Catalog catalog = new Catalog(
                List.of("MAD"), List.of("JFK"), List.of("IB"), List.of(), List.of(new Route("MAD", List.of("JFK"))));
        when(query.find(NOW)).thenReturn(catalog);

        assertThat(new CatalogService(query, Clock.fixed(NOW, ZoneOffset.UTC)).catalog())
                .isEqualTo(catalog);
    }
}
