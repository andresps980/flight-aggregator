package com.andresps.flights.search;

import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.complete;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.itineraries;
import static com.andresps.flights.search.ItinerarySearchIT.keys;
import static com.andresps.flights.search.SearchRows.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.PostgresDaySnapshotStore;
import com.andresps.flights.support.TestRedis;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec 005 AC-18 – AC-21: the Redis cache over real HTTP, invalidated through the real ingestion write path. */
class SearchCacheIT extends SearchSpringTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-07");
    private static final String SEARCH = "/api/v1/itineraries?origin=MAD&destination=JFK";

    @Autowired
    private PostgresDaySnapshotStore snapshots;

    @AfterEach
    void resumeRedis() {
        TestRedis.unpause();
    }

    private JsonNode ok(String path) {
        ResponseEntity<JsonNode> response = get(path);
        assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private long seedOne() {
        long id = rows.insert(
                row("MAD", "JFK", "IB", "2026-11-07T10:00:00Z", "400.00").withKey("k1"));
        rows.completeDay(DAY);
        rows.publish();
        return id;
    }

    @Test
    @DisplayName("AC-18: a repeated search with the same data version is served from Redis, not PostgreSQL")
    void searchHit() {
        long id = seedOne();
        JsonNode first = ok(SEARCH);
        double hits = cacheCount("hit");

        rows.changePriceBehindTheCache(id, "1.00");
        JsonNode second = ok(SEARCH);

        assertThat(second).isEqualTo(first);
        assertThat(second.get("items").get(0).get("finalPrice").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(cacheCount("hit")).isEqualTo(hits + 1);
        assertThat(TestRedis.keys()).isPositive();
    }

    @Test
    @DisplayName("AC-18: the catalog is served from Redis while the data version is unchanged")
    void catalogHit() {
        seedOne();
        JsonNode first = ok("/api/v1/catalog");

        rows.insert(row("BCN", "LHR", "VY", "2026-11-07T12:00:00Z", "90.00"));
        JsonNode second = ok("/api/v1/catalog");

        assertThat(second).isEqualTo(first);
        assertThat(second.get("origins").toString()).isEqualTo("[\"MAD\"]");
    }

    @Test
    @DisplayName("AC-19: after an ingestion replaces a day, the same search and the catalog show the new data")
    void invalidatedByReplacement() {
        UUID run = UUID.randomUUID();
        snapshots.replace(complete(DAY, run, itineraries(DAY, 3)));
        String search = "/api/v1/itineraries?origin=MAD&limit=100";
        assertThat(ok(search).get("items").size()).isEqualTo(3);
        assertThat(ok("/api/v1/catalog").get("destinations").toString()).isEqualTo("[\"BCN\",\"LHR\"]");

        snapshots.replace(complete(DAY, UUID.randomUUID(), itineraries(DAY, 5)));

        assertThat(ok(search).get("items").size()).isEqualTo(5);
        assertThat(cacheCount("miss")).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("AC-19: after an ingestion marks a day stale, the cached freshness is not served")
    void invalidatedByStaleMark() {
        snapshots.replace(complete(DAY, UUID.randomUUID(), itineraries(DAY, 2)));
        String search = "/api/v1/itineraries?origin=MAD";
        assertThat(ok(search).get("items").get(0).get("freshness").get("status").asString())
                .isEqualTo("COMPLETE");

        snapshots.markStale(DAY, UUID.randomUUID(), Instant.parse("2026-11-06T11:59:00Z"));

        assertThat(ok(search).get("items").get(0).get("freshness").get("status").asString())
                .isEqualTo("STALE");
    }

    @Test
    @DisplayName("AC-20: a cached page omits items departed since; it may be short or empty with hasMore true")
    void departedItemsOnCachedPages() {
        rows.insertAll(List.of(
                row("MAD", "JFK", "IB", "2026-11-06T12:30:00Z", "100.00").withKey("a"),
                row("MAD", "JFK", "IB", "2026-11-06T15:00:00Z", "200.00").withKey("b"),
                row("MAD", "JFK", "IB", "2026-11-06T16:00:00Z", "300.00").withKey("c")));
        rows.completeDay(LocalDate.parse("2026-11-06"));
        rows.publish();
        JsonNode first = ok(SEARCH + "&limit=2");
        assertThat(keys(first)).containsExactly("a", "b");
        double hits = cacheCount("hit");

        CLOCK.set(Instant.parse("2026-11-06T12:31:00Z"));
        JsonNode shorter = ok(SEARCH + "&limit=2");

        assertThat(cacheCount("hit")).isEqualTo(hits + 1);
        assertThat(keys(shorter)).containsExactly("b");
        assertThat(shorter.get("hasMore").asBoolean()).isTrue();
        assertThat(shorter.get("nextCursor")).isEqualTo(first.get("nextCursor"));

        CLOCK.set(Instant.parse("2026-11-06T15:30:00Z"));
        JsonNode empty = ok(SEARCH + "&limit=2");

        assertThat(keys(empty)).isEmpty();
        assertThat(empty.get("hasMore").asBoolean()).isTrue();
        assertThat(keys(ok(SEARCH + "&limit=2&cursor=" + empty.get("nextCursor").asString())))
                .containsExactly("c");
    }

    @Test
    @DisplayName("AC-21: with Redis hung, search and catalog answer from PostgreSQL; the breaker skips Redis; "
            + "requests go back to Redis once it recovers")
    void redisHungThenRecovers() {
        long id = seedOne();
        JsonNode cached = ok(SEARCH);
        JsonNode catalog = ok("/api/v1/catalog");
        rows.changePriceBehindTheCache(id, "399.00");

        TestRedis.pause();
        for (int i = 0; i < 12; i++) {
            long start = System.nanoTime();
            JsonNode fromPostgres = ok(SEARCH);
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(fromPostgres.get("items").get(0).get("finalPrice").decimalValue())
                    .isEqualByComparingTo("399.00");
            assertThat(took).isLessThan(Duration.ofSeconds(2));
        }
        assertThat(ok("/api/v1/catalog")).isEqualTo(catalog);
        assertThat(cacheCount("error")).isPositive();
        assertThat(cacheBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        double bypassed = cacheCount("bypassed");
        ok(SEARCH);
        assertThat(cacheCount("bypassed")).isGreaterThan(bypassed);

        TestRedis.unpause();
        CLOCK.advance(Duration.ofSeconds(31));
        double hits = cacheCount("hit");
        JsonNode again = ok(SEARCH);
        ok(SEARCH);

        // the entry cached before the outage is still valid (same data version): served from Redis again
        assertThat(again).isEqualTo(cached);
        assertThat(cacheCount("hit")).isGreaterThan(hits);
        assertThat(cacheBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
