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
import com.andresps.flights.search.domain.CatalogDate;
import com.andresps.flights.search.domain.Freshness;
import com.andresps.flights.search.domain.Route;
import com.andresps.flights.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    @DisplayName("AC-21 / review: concurrent misses compute the catalog once per version, also with Redis down")
    void singleFlightPerVersion() throws Exception {
        redis.failing = true;
        CountDownLatch inQuery = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(postgres.find(any())).thenAnswer(invocation -> {
            inQuery.countDown();
            release.await(5, TimeUnit.SECONDS);
            return CATALOG;
        });
        CachingCatalogQuery resilient = new CachingCatalogQuery(
                postgres,
                versions,
                new ResilientCacheStore(redis, ResilientCacheStore.circuitBreaker(new MutableClock()), metrics),
                new SearchCacheCodec(),
                metrics,
                Duration.ofMinutes(10));

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<Catalog>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> resilient.find(NOW)));
            }
            assertThat(inQuery.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (Future<Catalog> result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(CATALOG);
            }
        }

        verify(postgres, times(1)).find(any());
    }

    @Test
    @DisplayName("AC-17 / review: a reused catalog never offers a day before today (UTC)")
    void dropsPastDates() {
        Freshness fresh = Freshness.of(true, NOW, List.of(), null);
        Catalog withDays = new Catalog(
                List.of("MAD"),
                List.of("JFK"),
                List.of("IB"),
                List.of(
                        new CatalogDate(LocalDate.parse("2026-11-06"), fresh),
                        new CatalogDate(LocalDate.parse("2026-11-07"), fresh)),
                List.of(new Route("MAD", List.of("JFK"))));
        when(postgres.find(any())).thenReturn(withDays);

        assertThat(cached.find(NOW).dates()).hasSize(2);
        Catalog afterMidnight = cached.find(Instant.parse("2026-11-07T00:00:01Z"));

        assertThat(afterMidnight.dates()).extracting(CatalogDate::date).containsExactly(LocalDate.parse("2026-11-07"));
        verify(postgres, times(1)).find(any());
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
