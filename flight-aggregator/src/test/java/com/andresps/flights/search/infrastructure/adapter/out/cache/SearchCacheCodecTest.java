package com.andresps.flights.search.infrastructure.adapter.out.cache;

import static com.andresps.flights.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.search.domain.Catalog;
import com.andresps.flights.search.domain.CatalogDate;
import com.andresps.flights.search.domain.Freshness;
import com.andresps.flights.search.domain.ItineraryView;
import com.andresps.flights.search.domain.PagePosition;
import com.andresps.flights.search.domain.Route;
import com.andresps.flights.search.domain.SearchSlice;
import com.andresps.flights.search.domain.SegmentView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchCacheCodecTest {

    private final SearchCacheCodec codec = new SearchCacheCodec();

    @Test
    @DisplayName("AC-18: a cached slice reads back equal (prices keep scale 2, instants, nulls, segments in order)")
    void sliceRoundTrip() {
        ItineraryView stale = new ItineraryView(
                "IB1:MAD-ZRH@2026-11-07T08:00:00Z|IB2:ZRH-JFK@2026-11-07T11:00:00Z",
                "MAD",
                "JFK",
                LocalDate.parse("2026-11-07"),
                Instant.parse("2026-11-07T08:00:00Z"),
                Instant.parse("2026-11-07T19:00:00Z"),
                "IB",
                1,
                List.of(
                        new SegmentView(
                                "IB1",
                                "MAD",
                                "ZRH",
                                Instant.parse("2026-11-07T08:00:00Z"),
                                Instant.parse("2026-11-07T10:00:00Z")),
                        new SegmentView(
                                "IB2",
                                "ZRH",
                                "JFK",
                                Instant.parse("2026-11-07T11:00:00Z"),
                                Instant.parse("2026-11-07T19:00:00Z"))),
                new BigDecimal("100.00"),
                new BigDecimal("105.00"),
                "mock-beta",
                List.of("mock-beta"),
                Freshness.of(
                        false,
                        Instant.parse("2026-11-06T10:00:00Z"),
                        List.of("mock-alpha"),
                        Instant.parse("2026-11-06T10:15:00Z")));
        SearchSlice withNext = new SearchSlice(
                List.of(view("A", "2026-11-07T10:00:00Z", "433.00"), stale),
                new PagePosition(new BigDecimal("105.00"), 42L));
        SearchSlice last = new SearchSlice(List.of(view("B", "2026-11-07T10:00:00Z", "1.10")), null);

        assertThat(codec.readSlice(codec.write(withNext))).isEqualTo(withNext);
        assertThat(codec.readSlice(codec.write(last))).isEqualTo(last);
        assertThat(codec.readSlice(codec.write(new SearchSlice(List.of(), null)))
                        .items())
                .isEmpty();
    }

    @Test
    @DisplayName("AC-18: a cached catalog reads back equal")
    void catalogRoundTrip() {
        Catalog catalog = new Catalog(
                List.of("BCN", "MAD"),
                List.of("JFK", "LHR"),
                List.of("IB", "VY"),
                List.of(new CatalogDate(
                        LocalDate.parse("2026-11-06"),
                        Freshness.of(true, Instant.parse("2026-11-06T10:00:00Z"), List.of(), null))),
                List.of(new Route("BCN", List.of("LHR")), new Route("MAD", List.of("JFK", "LHR"))));

        assertThat(codec.readCatalog(codec.write(catalog))).isEqualTo(catalog);
    }

    @Test
    @DisplayName("ADR-0022: an unreadable value fails to decode (the decorator then treats it as a miss)")
    void unreadable() {
        assertThatThrownBy(() -> codec.readSlice("{not json")).isInstanceOf(RuntimeException.class);
    }
}
