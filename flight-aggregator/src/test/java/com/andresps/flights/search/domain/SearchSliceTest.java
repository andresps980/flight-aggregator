package com.andresps.flights.search.domain;

import static com.andresps.flights.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchSliceTest {

    @Test
    @DisplayName("AC-20: departed items are dropped, the next position is kept (so the page may be short or empty)")
    void dropsDepartedKeepsNext() {
        PagePosition next = new PagePosition(new BigDecimal("300.00"), 9L);
        SearchSlice slice = new SearchSlice(
                List.of(
                        view("A", "2026-11-06T12:00:00Z", "100.00"),
                        view("B", "2026-11-06T12:30:00Z", "200.00"),
                        view("C", "2026-11-06T13:00:00Z", "300.00")),
                next);

        SearchSlice atHalfPast = slice.withoutDepartedAt(Instant.parse("2026-11-06T12:30:00Z"));
        SearchSlice afterAll = slice.withoutDepartedAt(Instant.parse("2026-11-07T00:00:00Z"));

        // "departed" = first departure not strictly after now (spec 001 Q5): 12:30 has departed at 12:30
        assertThat(atHalfPast.items()).extracting(ItineraryView::itineraryKey).containsExactly("C");
        assertThat(atHalfPast.next()).isEqualTo(next);
        assertThat(atHalfPast.hasMore()).isTrue();
        assertThat(afterAll.items()).isEmpty();
        assertThat(afterAll.hasMore()).isTrue();
    }

    @Test
    @DisplayName("AC-7: a slice without a next position has no more pages")
    void lastSlice() {
        assertThat(new SearchSlice(List.of(), null).hasMore()).isFalse();
    }
}
