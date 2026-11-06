package com.andresps.flights.search.infrastructure.adapter.out.cache;

import static com.andresps.flights.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.andresps.flights.search.application.port.out.DataVersionQuery;
import com.andresps.flights.search.application.port.out.ItinerarySearchQuery;
import com.andresps.flights.search.domain.ItineraryView;
import com.andresps.flights.search.domain.PagePosition;
import com.andresps.flights.search.domain.SearchCriteria;
import com.andresps.flights.search.domain.SearchSlice;
import com.andresps.flights.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CachingItinerarySearchQueryTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final SearchCriteria MAD =
            SearchCriteria.parse("MAD", null, null, null, null, LocalDate.parse("2026-11-06"));
    private static final PagePosition NEXT = new PagePosition(new BigDecimal("300.00"), 3L);
    private static final SearchSlice SLICE = new SearchSlice(
            List.of(view("A", "2026-11-06T12:30:00Z", "100.00"), view("B", "2026-11-06T14:00:00Z", "300.00")), NEXT);

    private final ItinerarySearchQuery postgres = mock(ItinerarySearchQuery.class);
    private final DataVersionQuery versions = mock(DataVersionQuery.class);
    private final InMemoryCacheStore redis = new InMemoryCacheStore();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CacheMetrics metrics = new CacheMetrics(registry);
    private CachingItinerarySearchQuery cached;

    @BeforeEach
    void setUp() {
        when(versions.current()).thenReturn(1L);
        when(postgres.find(any(), any(), anyInt(), any())).thenReturn(SLICE);
        cached = decorating(redis);
    }

    private CachingItinerarySearchQuery decorating(CacheStore store) {
        return new CachingItinerarySearchQuery(postgres, versions, store, new SearchCacheCodec(), metrics, TTL);
    }

    private double count(String result) {
        return registry.counter(CacheMetrics.NAME, "result", result).count();
    }

    @Test
    @DisplayName("AC-18: a miss reads PostgreSQL and stores the slice with the 10 min TTL under the versioned key")
    void missStores() {
        SearchSlice result = cached.find(MAD, null, 20, NOW);

        assertThat(result).isEqualTo(SLICE);
        String key = SearchCacheKeys.search(1, MAD, null, 20);
        assertThat(redis.values).containsKey(key);
        assertThat(redis.ttls.get(key)).isEqualTo(TTL);
        assertThat(count("miss")).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-18: a repeated request with the same data version is served from the cache")
    void hitDoesNotQueryPostgres() {
        cached.find(MAD, null, 20, NOW);

        SearchSlice second = cached.find(MAD, null, 20, NOW);

        assertThat(second).isEqualTo(SLICE);
        verify(postgres, times(1)).find(any(), any(), anyInt(), any());
        assertThat(count("hit")).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-19: a new data version misses (old entries are left to expire, never deleted)")
    void newVersionMisses() {
        cached.find(MAD, null, 20, NOW);
        when(versions.current()).thenReturn(2L);

        cached.find(MAD, null, 20, NOW);

        verify(postgres, times(2)).find(any(), any(), anyInt(), any());
        assertThat(redis.values)
                .containsKeys(SearchCacheKeys.search(1, MAD, null, 20), SearchCacheKeys.search(2, MAD, null, 20));
    }

    @Test
    @DisplayName("AC-18: another position or limit is another entry")
    void positionAndLimitAreKeyed() {
        cached.find(MAD, null, 20, NOW);
        cached.find(MAD, NEXT, 20, NOW);
        cached.find(MAD, null, 10, NOW);

        verify(postgres, times(3)).find(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("AC-20: a hit drops items that departed since they were cached and keeps the next position")
    void hitDropsDeparted() {
        cached.find(MAD, null, 20, NOW);

        SearchSlice later = cached.find(MAD, null, 20, Instant.parse("2026-11-06T13:00:00Z"));

        assertThat(later.items()).extracting(ItineraryView::itineraryKey).containsExactly("B");
        assertThat(later.next()).isEqualTo(NEXT);
    }

    @Test
    @DisplayName("AC-21: with Redis failing, the slice comes from PostgreSQL")
    void redisFailureFallsBack() {
        redis.failing = true;
        CachingItinerarySearchQuery resilient = decorating(
                new ResilientCacheStore(redis, ResilientCacheStore.circuitBreaker(new MutableClock()), metrics));

        assertThat(resilient.find(MAD, null, 20, NOW)).isEqualTo(SLICE);
        assertThat(resilient.find(MAD, null, 20, NOW)).isEqualTo(SLICE);

        verify(postgres, times(2)).find(any(), any(), anyInt(), any());
        assertThat(count("error")).isPositive();
    }

    @Test
    @DisplayName("ADR-0022: an unreadable cached value is a miss and is overwritten")
    void unreadableValueIsAMiss() {
        redis.values.put(SearchCacheKeys.search(1, MAD, null, 20), "{corrupt");

        assertThat(cached.find(MAD, null, 20, NOW)).isEqualTo(SLICE);
        assertThat(cached.find(MAD, null, 20, NOW)).isEqualTo(SLICE);

        verify(postgres, times(1)).find(any(), any(), anyInt(), any());
    }
}
