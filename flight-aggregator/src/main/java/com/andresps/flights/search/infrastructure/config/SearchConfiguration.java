package com.andresps.flights.search.infrastructure.config;

import com.andresps.flights.search.application.port.out.CatalogQuery;
import com.andresps.flights.search.application.port.out.DataVersionQuery;
import com.andresps.flights.search.application.port.out.ItinerarySearchQuery;
import com.andresps.flights.search.application.service.CatalogService;
import com.andresps.flights.search.application.service.ItinerarySearchService;
import com.andresps.flights.search.infrastructure.adapter.out.cache.CacheMetrics;
import com.andresps.flights.search.infrastructure.adapter.out.cache.CacheStore;
import com.andresps.flights.search.infrastructure.adapter.out.cache.CachingCatalogQuery;
import com.andresps.flights.search.infrastructure.adapter.out.cache.CachingItinerarySearchQuery;
import com.andresps.flights.search.infrastructure.adapter.out.cache.RedisCacheStore;
import com.andresps.flights.search.infrastructure.adapter.out.cache.ResilientCacheStore;
import com.andresps.flights.search.infrastructure.adapter.out.cache.SearchCacheCodec;
import com.andresps.flights.search.infrastructure.adapter.out.persistence.PostgresCatalogQuery;
import com.andresps.flights.search.infrastructure.adapter.out.persistence.PostgresDataVersionQuery;
import com.andresps.flights.search.infrastructure.adapter.out.persistence.PostgresItinerarySearchQuery;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Composition root of the search feature (spec 005): the PostgreSQL query adapters, decorated with the Redis cache
 * when it is enabled (ADR-0022), and the two use cases. Exactly one bean per outbound port, so the use cases never know
 * whether a cache is in front.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
class SearchConfiguration {

    @Bean
    DataVersionQuery dataVersionQuery(DataSource dataSource) {
        return new PostgresDataVersionQuery(dataSource);
    }

    @Bean
    CacheMetrics searchCacheMetrics(MeterRegistry registry) {
        return new CacheMetrics(registry);
    }

    @Bean
    CircuitBreaker searchCacheCircuitBreaker(Clock clock) {
        return ResilientCacheStore.circuitBreaker(clock);
    }

    @Bean
    CacheStore searchCacheStore(
            StringRedisTemplate redis, CircuitBreaker searchCacheCircuitBreaker, CacheMetrics searchCacheMetrics) {
        return new ResilientCacheStore(new RedisCacheStore(redis), searchCacheCircuitBreaker, searchCacheMetrics);
    }

    @Bean
    ItinerarySearchQuery itinerarySearchQuery(
            DataSource dataSource,
            SearchProperties properties,
            DataVersionQuery dataVersionQuery,
            CacheStore searchCacheStore,
            CacheMetrics searchCacheMetrics) {
        ItinerarySearchQuery postgres = new PostgresItinerarySearchQuery(dataSource);
        if (!properties.cache().enabled()) {
            return postgres;
        }
        return new CachingItinerarySearchQuery(
                postgres,
                dataVersionQuery,
                searchCacheStore,
                new SearchCacheCodec(),
                searchCacheMetrics,
                properties.cache().ttl());
    }

    @Bean
    CatalogQuery catalogQuery(
            DataSource dataSource,
            SearchProperties properties,
            DataVersionQuery dataVersionQuery,
            CacheStore searchCacheStore,
            CacheMetrics searchCacheMetrics) {
        CatalogQuery postgres = new PostgresCatalogQuery(dataSource);
        if (!properties.cache().enabled()) {
            return postgres;
        }
        return new CachingCatalogQuery(
                postgres,
                dataVersionQuery,
                searchCacheStore,
                new SearchCacheCodec(),
                searchCacheMetrics,
                properties.cache().ttl());
    }

    @Bean
    ItinerarySearchService searchItinerariesUseCase(ItinerarySearchQuery itinerarySearchQuery, Clock clock) {
        return new ItinerarySearchService(itinerarySearchQuery, clock);
    }

    @Bean
    CatalogService getCatalogUseCase(CatalogQuery catalogQuery, Clock clock) {
        return new CatalogService(catalogQuery, clock);
    }
}
