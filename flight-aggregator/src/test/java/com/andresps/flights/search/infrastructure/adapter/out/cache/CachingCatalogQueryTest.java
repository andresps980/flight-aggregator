package com.andresps.flights.search.infrastructure.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.andresps.flights.search.application.port.out.CatalogQuery;
import com.andresps.flights.search.application.port.out.DataVersionQuery;
import com.andresps.flights.search.domain.Catalog;
import com.andresps.flights.search.domain.Route;
import com.andresps.flights.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CachingCatalogQueryTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private static final Catalog CATALOG = new Catalog(
            List.of("MAD"), List.of("JFK"), List.of("IB"), List.of(), List.of(new Route("MAD", List.of("JFK"))));

    private final CatalogQuery postgres = mock(CatalogQuery.class);
    private final DataVersionQuery versions = mock(DataVersionQuery.class);
    private final InMemoryCacheStore redis = new InMemoryCacheStore();
    private final CacheMetrics metrics = new CacheMetrics(new SimpleMeterRegistry());
    private CachingCatalogQuery cached;

    @BeforeEach
    void setUp() {
        when(versions.current()).thenReturn(1L);
        when(postgres.find(any())).thenReturn(CATALOG);
        cached = new CachingCatalogQuery(
                postgres, versions, redis, new SearchCacheCodec(), metrics, Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("AC-17 / AC-18: the catalog is computed once per data version")
    void cachedPerVersion() {
        assertThat(cached.find(NOW)).isEqualTo(CATALOG);
        assertThat(cached.find(NOW)).isEqualTo(CATALOG);
        verify(postgres, times(1)).find(any());
        assertThat(redis.values).containsKey(SearchCacheKeys.catalog(1));

        when(versions.current()).thenReturn(2L);
        cached.find(NOW);
        verify(postgres, times(2)).find(any());
    }

    @Test
    @DisplayName("AC-21: with Redis failing, the catalog comes from PostgreSQL")
    void redisFailureFallsBack() {
        redis.failing = true;
        CachingCatalogQuery resilient = new CachingCatalogQuery(
                postgres,
                versions,
                new ResilientCacheStore(redis, ResilientCacheStore.circuitBreaker(new MutableClock()), metrics),
                new SearchCacheCodec(),
                metrics,
                Duration.ofMinutes(10));

        assertThat(resilient.find(NOW)).isEqualTo(CATALOG);
    }
}
