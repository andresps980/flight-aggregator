package com.andresps.flights.search.application.service;

import static com.andresps.flights.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.andresps.flights.search.application.port.in.SearchRequest;
import com.andresps.flights.search.application.port.out.ItinerarySearchQuery;
import com.andresps.flights.search.domain.Cursor;
import com.andresps.flights.search.domain.InvalidSearchRequestException;
import com.andresps.flights.search.domain.ItineraryView;
import com.andresps.flights.search.domain.PagePosition;
import com.andresps.flights.search.domain.SearchCriteria;
import com.andresps.flights.search.domain.SearchPage;
import com.andresps.flights.search.domain.SearchSlice;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ItinerarySearchServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-11-06");

    private final ItinerarySearchQuery query = mock(ItinerarySearchQuery.class);
    private final ItinerarySearchService service = new ItinerarySearchService(query, Clock.fixed(NOW, ZoneOffset.UTC));

    private static SearchRequest request(String origin, String destination, String limit, String cursor) {
        return new SearchRequest(origin, destination, null, null, null, limit, cursor);
    }

    private static ItineraryView item(String key, String price) {
        return view(key, "2026-11-07T10:00:00Z", price);
    }

    @Test
    @DisplayName("AC-3 / AC-4 / AC-6: the first page asks the query for 20 items from the start, judged at now")
    void firstPageDefaults() {
        when(query.find(any(), any(), anyInt(), any())).thenReturn(new SearchSlice(List.of(), null));

        service.search(request(null, null, null, null));

        verify(query).find(eq(SearchCriteria.none()), isNull(), eq(20), eq(NOW));
    }

    @Test
    @DisplayName("AC-7: with more results the page carries a cursor for the next position and the same filters")
    void nextCursor() {
        PagePosition next = new PagePosition(new BigDecimal("433.00"), 82134L);
        when(query.find(any(), any(), anyInt(), any()))
                .thenReturn(new SearchSlice(List.of(item("A", "400.00"), item("B", "433.00")), next));

        SearchPage page = service.search(request("mad", "JFK", "2", null));

        SearchCriteria criteria = SearchCriteria.parse("MAD", "JFK", null, null, null, TODAY);
        assertThat(page.items()).extracting(ItineraryView::itineraryKey).containsExactly("A", "B");
        assertThat(page.hasMore()).isTrue();
        assertThat(Cursor.decode(page.nextCursor())).isEqualTo(new Cursor(next, criteria.fingerprint()));
    }

    @Test
    @DisplayName("AC-7: the last page has no cursor")
    void lastPage() {
        when(query.find(any(), any(), anyInt(), any())).thenReturn(new SearchSlice(List.of(item("A", "1.00")), null));

        SearchPage page = service.search(request(null, null, null, null));

        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("AC-7: a cursor continues from its position; the limit may change between pages")
    void continuesFromTheCursor() {
        SearchCriteria criteria = SearchCriteria.parse("MAD", null, null, null, null, TODAY);
        PagePosition position = new PagePosition(new BigDecimal("99.90"), 7L);
        String cursor = new Cursor(position, criteria.fingerprint()).encode();
        when(query.find(any(), any(), anyInt(), any())).thenReturn(new SearchSlice(List.of(), null));

        service.search(request("MAD", null, "50", cursor));

        verify(query).find(criteria, position, 50, NOW);
    }

    @Test
    @DisplayName("AC-20: a short page keeps hasMore and continues from the slice's next position, not its last item")
    void shortPageKeepsNext() {
        PagePosition next = new PagePosition(new BigDecimal("500.00"), 40L);
        when(query.find(any(), any(), anyInt(), any())).thenReturn(new SearchSlice(List.of(), next));

        SearchPage page = service.search(request(null, null, "20", null));

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isTrue();
        assertThat(Cursor.decode(page.nextCursor()).position()).isEqualTo(next);
    }

    @Test
    @DisplayName("AC-16: a cursor issued for different filters is rejected and nothing is queried")
    void rejectsCursorForOtherFilters() {
        String cursor = new Cursor(
                        new PagePosition(BigDecimal.ONE, 1L),
                        SearchCriteria.parse("MAD", null, null, null, null, TODAY)
                                .fingerprint())
                .encode();

        assertThatThrownBy(() -> service.search(request("BCN", null, null, cursor)))
                .isInstanceOf(InvalidSearchRequestException.class)
                .hasMessageContaining("cursor");
        verifyNoInteractions(query);
    }

    @Test
    @DisplayName("AC-12..AC-16: invalid parameters are rejected before querying")
    void validatesBeforeQuerying() {
        assertThatThrownBy(() -> service.search(request("MADRID", null, null, null)))
                .isInstanceOf(InvalidSearchRequestException.class);
        assertThatThrownBy(() -> service.search(request(null, null, "500", null)))
                .isInstanceOf(InvalidSearchRequestException.class);
        assertThatThrownBy(() -> service.search(request(null, null, null, "garbage!")))
                .isInstanceOf(InvalidSearchRequestException.class);
        assertThatThrownBy(() -> service.search(new SearchRequest(null, null, "2030-01-01", null, null, null, null)))
                .isInstanceOf(InvalidSearchRequestException.class);
        verifyNoInteractions(query);
    }

    @Test
    @DisplayName("AC-15: the date range is relative to today in UTC, read from the clock")
    void dateRangeFollowsTheClock() {
        when(query.find(any(), any(), anyInt(), any())).thenReturn(new SearchSlice(List.of(), null));
        ItinerarySearchService lateEvening =
                new ItinerarySearchService(query, Clock.fixed(Instant.parse("2026-11-06T23:59:59Z"), ZoneOffset.UTC));

        lateEvening.search(new SearchRequest(null, null, "2026-11-05", null, null, null, null));

        assertThatThrownBy(
                        () -> lateEvening.search(new SearchRequest(null, null, "2026-11-04", null, null, null, null)))
                .isInstanceOf(InvalidSearchRequestException.class);
    }
}
