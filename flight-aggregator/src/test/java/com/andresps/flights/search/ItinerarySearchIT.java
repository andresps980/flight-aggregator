package com.andresps.flights.search;

import static com.andresps.flights.search.SearchRows.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Spec 005 AC-1 – AC-16 over real HTTP: {@code GET /api/v1/itineraries}. "Now" is 2026-11-06T12:00:00Z. */
class ItinerarySearchIT extends SearchSpringTest {

    private static final String PATH = "/api/v1/itineraries";

    static List<String> keys(JsonNode page) {
        List<String> keys = new ArrayList<>();
        JsonNode items = page.get("items");
        for (int i = 0; i < items.size(); i++) {
            keys.add(items.get(i).get("itineraryKey").asString());
        }
        return keys;
    }

    private JsonNode ok(String query) {
        ResponseEntity<JsonNode> response = get(PATH + query);
        assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** Walks every page with {@code limit} and returns the keys in the order served. */
    private List<String> walk(String filters, int limit) {
        List<String> served = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 1000; guard++) {
            JsonNode page = ok("?limit=" + limit + filters + (cursor == null ? "" : "&cursor=" + cursor));
            served.addAll(keys(page));
            if (!page.get("hasMore").asBoolean()) {
                assertThat(page.get("nextCursor").isNull()).isTrue();
                return served;
            }
            cursor = page.get("nextCursor").asString();
        }
        throw new AssertionError("pagination did not end");
    }

    @Nested
    class Filters {

        @BeforeEach
        void seed() {
            rows.insertAll(List.of(
                    row("MAD", "JFK", "IB", "2026-11-07T10:00:00Z", "400.00").withKey("k1"),
                    row("MAD", "JFK", "VY", "2026-11-07T11:00:00Z", "350.00").withKey("k2"),
                    row("MAD", "JFK", "IB", "2026-11-08T09:00:00Z", "650.00").withKey("k3"),
                    row("MAD", "JFK", "IB", "2026-11-08T15:00:00Z", "650.01").withKey("k4"),
                    row("MAD", "LHR", "IB", "2026-11-07T08:00:00Z", "120.00").withKey("k5"),
                    row("BCN", "JFK", "IB", "2026-11-07T13:00:00Z", "500.00").withKey("k6"),
                    row("BCN", "LHR", "VY", "2026-11-06T18:00:00Z", "90.00").withKey("k7"),
                    // departed before now, and departing exactly now (not strictly after: departed)
                    row("MAD", "JFK", "IB", "2026-11-06T11:00:00Z", "100.00").withKey("departed"),
                    row("MAD", "JFK", "IB", "2026-11-06T12:00:00Z", "101.00").withKey("departing-now")));
            List.of("2026-11-06", "2026-11-07", "2026-11-08").forEach(d -> rows.completeDay(LocalDate.parse(d)));
            rows.publish();
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(
                delimiter = ';',
                value = {
                    "'';                                                   k7 k5 k2 k1 k6 k3 k4",
                    "&origin=MAD;                                          k5 k2 k1 k3 k4",
                    "&destination=JFK;                                     k2 k1 k6 k3 k4",
                    "&carrier=VY;                                          k7 k2",
                    "&maxPrice=650;                                        k7 k5 k2 k1 k6 k3",
                    "&maxPrice=649.99;                                     k7 k5 k2 k1 k6",
                    "&maxPrice=650.01;                                     k7 k5 k2 k1 k6 k3 k4",
                    "&date=2026-11-08;                                     k3 k4",
                    "&origin=MAD&destination=JFK;                          k2 k1 k3 k4",
                    "&origin=MAD&destination=JFK&carrier=IB;               k1 k3 k4",
                    "&origin=MAD&destination=JFK&date=2026-11-07;          k2 k1",
                    "&origin=MAD&carrier=IB&maxPrice=400;                  k5 k1",
                    "&origin=MAD&destination=JFK&date=2026-11-08&carrier=IB&maxPrice=650; k3",
                })
        @DisplayName("AC-1: each filter and their combinations return exactly the matching future itineraries")
        void filters(String query, String expected) {
            assertThat(keys(ok("?limit=100" + query.trim())))
                    .containsExactly(expected.trim().split("\\s+"));
        }

        @Test
        @DisplayName("AC-1: maxPrice is inclusive: exactly maxPrice is returned, one cent above is not")
        void maxPriceBoundary() {
            assertThat(keys(ok("?maxPrice=650.00&origin=MAD&destination=JFK")))
                    .contains("k3")
                    .doesNotContain("k4");
        }

        @Test
        @DisplayName("AC-2: codes are case-insensitive and trimmed; 100 and 100.00 are the same maxPrice")
        void normalization() {
            assertThat(keys(ok("?origin=mad&destination=%20jfk%20&carrier=ib")))
                    .isEqualTo(keys(ok("?origin=MAD&destination=JFK&carrier=IB")));
            assertThat(keys(ok("?maxPrice=400"))).isEqualTo(keys(ok("?maxPrice=400.00")));
        }

        @Test
        @DisplayName("AC-3: without date every future day is eligible; with date only that UTC day")
        void dateDefault() {
            assertThat(keys(ok("?origin=MAD&destination=JFK"))).containsExactly("k2", "k1", "k3", "k4");
            assertThat(keys(ok("?date=2026-11-07"))).containsExactly("k5", "k2", "k1", "k6");
        }

        @Test
        @DisplayName("AC-4: departed itineraries are never returned, with or without date")
        void departedExcluded() {
            assertThat(keys(ok("?limit=100"))).doesNotContain("departed", "departing-now");
            assertThat(keys(ok("?date=2026-11-06"))).containsExactly("k7");
            assertThat(keys(ok("?origin=MAD&destination=JFK&maxPrice=101"))).isEmpty();
        }

        @Test
        @DisplayName("AC-4: an itinerary disappears from search once it departs")
        void departsWhileStored() {
            CLOCK.set(Instant.parse("2026-11-06T18:00:00Z"));

            assertThat(keys(ok("?date=2026-11-06"))).isEmpty();
            assertThat(keys(ok("?limit=100"))).doesNotContain("k7");
        }

        @Test
        @DisplayName("AC-11 / Q9: no match (unknown carrier, origin equal to destination, maxPrice 0) is an empty page")
        void noMatch() {
            for (String query : List.of("?carrier=U2", "?origin=MAD&destination=MAD", "?maxPrice=0")) {
                JsonNode page = ok(query);
                assertThat(page.get("items").size()).as(query).isZero();
                assertThat(page.get("hasMore").asBoolean()).isFalse();
                assertThat(page.get("nextCursor").isNull()).isTrue();
            }
        }
    }

    @Nested
    class OrderingAndPagination {

        @Test
        @DisplayName("AC-5: ordered by final price, then by id: equal prices keep insertion (id) order")
        void orderingWithTies() {
            rows.insertAll(List.of(
                    row("MAD", "JFK", "IB", "2026-11-07T10:00:00Z", "200.00").withKey("t1"),
                    row("MAD", "JFK", "IB", "2026-11-07T11:00:00Z", "150.00").withKey("cheap"),
                    row("MAD", "JFK", "IB", "2026-11-07T12:00:00Z", "200.00").withKey("t2"),
                    row("MAD", "JFK", "IB", "2026-11-07T07:00:00Z", "200.00").withKey("t3"),
                    row("MAD", "JFK", "IB", "2026-11-07T13:00:00Z", "999.99").withKey("dear"),
                    row("MAD", "JFK", "IB", "2026-11-07T09:00:00Z", "200.00").withKey("t4")));
            rows.completeDay(LocalDate.parse("2026-11-07"));
            rows.publish();

            assertThat(keys(ok(""))).containsExactly("cheap", "t1", "t2", "t3", "t4", "dear");
        }

        @Test
        @DisplayName("AC-6: limit defaults to 20 and may be 1-100")
        void limits() {
            seedTies(120);

            assertThat(ok("").get("items").size()).isEqualTo(20);
            assertThat(ok("?limit=1").get("items").size()).isEqualTo(1);
            assertThat(ok("?limit=100").get("items").size()).isEqualTo(100);
        }

        @Test
        @DisplayName("AC-7: the cursor returns the following items; limit may change; the last page ends the walk")
        void cursorContinues() {
            List<String> all = seedTies(12);

            JsonNode first = ok("?limit=5");
            assertThat(keys(first)).isEqualTo(all.subList(0, 5));
            assertThat(first.get("hasMore").asBoolean()).isTrue();

            JsonNode second = ok("?limit=3&cursor=" + first.get("nextCursor").asString());
            assertThat(keys(second)).isEqualTo(all.subList(5, 8));

            JsonNode last = ok("?limit=10&cursor=" + second.get("nextCursor").asString());
            assertThat(keys(last)).isEqualTo(all.subList(8, 12));
            assertThat(last.get("hasMore").asBoolean()).isFalse();
            assertThat(last.get("nextCursor").isNull()).isTrue();
        }

        @Test
        @DisplayName("AC-7: a page that ends exactly at the last item has no more pages")
        void exactLastPage() {
            seedTies(10);

            JsonNode page = ok("?limit=10");

            assertThat(page.get("items").size()).isEqualTo(10);
            assertThat(page.get("hasMore").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("AC-8: walking all pages yields every matching row exactly once, in order, ties across pages")
        void walkAllPages() {
            List<String> all = seedTies(57);

            for (int limit : List.of(1, 7, 20, 56, 57, 100)) {
                List<String> served = walk("", limit);
                assertThat(served).as("limit " + limit).isEqualTo(all);
                assertThat(new HashSet<>(served)).hasSize(57);
            }
            List<String> fromMad = all.stream().filter(k -> k.startsWith("MAD")).toList();
            assertThat(walk("&origin=MAD", 4)).isEqualTo(fromMad);
        }

        @Test
        @DisplayName("AC-9: the page has items, nextCursor and hasMore, and no total count")
        void noTotal() {
            seedTies(3);

            JsonNode page = ok("");
            Set<String> fields = new HashSet<>(page.propertyNames());

            assertThat(fields).containsExactlyInAnyOrder("items", "nextCursor", "hasMore");
        }

        /**
         * {@code count} itineraries on 4 prices (many ties), alternating MAD/BCN origins, over 3 future days; returns
         * their keys in the expected order (price, then insertion = id order).
         */
        private List<String> seedTies(int count) {
            record Seeded(String key, int price, int order) {}
            List<Seeded> seeded = new ArrayList<>();
            IntStream.range(0, count).forEach(i -> {
                String origin = i % 2 == 0 ? "MAD" : "BCN";
                int price = 100 + (i * 7) % 4;
                String key = origin + "-" + i;
                Instant departure = Instant.parse("2026-11-07T00:00:00Z").plus(Duration.ofMinutes(37L * i));
                rows.insert(row(origin, "JFK", "IB", departure.toString(), price + ".00")
                        .withKey(key));
                seeded.add(new Seeded(key, price, i));
            });
            List.of("2026-11-07", "2026-11-08", "2026-11-09", "2026-11-10")
                    .forEach(d -> rows.completeDay(LocalDate.parse(d)));
            rows.publish();
            return seeded.stream()
                    .sorted(Comparator.comparingInt(Seeded::price).thenComparingInt(Seeded::order))
                    .map(Seeded::key)
                    .toList();
        }
    }

    @Nested
    class Content {

        @Test
        @DisplayName("AC-10: an item carries key fields, segments, prices, providers, stops and its day's freshness")
        void itemContent() {
            rows.insert(row("MAD", "JFK", "IB", "2026-11-07T08:00:00Z", "105.00")
                    .withStops(1)
                    .withKey("IB100:MAD-ZRH@2026-11-07T08:00:00Z|IB101:ZRH-JFK@2026-11-07T11:00:00Z"));
            rows.completeDay(LocalDate.parse("2026-11-07"));
            rows.publish();

            JsonNode item = ok("").get("items").get(0);

            assertThat(item.get("itineraryKey").asString())
                    .isEqualTo("IB100:MAD-ZRH@2026-11-07T08:00:00Z|IB101:ZRH-JFK@2026-11-07T11:00:00Z");
            assertThat(item.get("origin").asString()).isEqualTo("MAD");
            assertThat(item.get("destination").asString()).isEqualTo("JFK");
            assertThat(item.get("departureDate").asString()).isEqualTo("2026-11-07");
            assertThat(item.get("departureAt").asString()).isEqualTo("2026-11-07T08:00:00Z");
            assertThat(item.get("arrivalAt").asString()).isEqualTo("2026-11-07T13:00:00Z");
            assertThat(item.get("carrier").asString()).isEqualTo("IB");
            assertThat(item.get("stops").asInt()).isEqualTo(1);
            assertThat(item.get("basePrice").decimalValue()).isEqualByComparingTo("100.00");
            assertThat(item.get("finalPrice").decimalValue()).isEqualByComparingTo("105.00");
            // prices are JSON numbers with 2 decimals (the tree above parses them as doubles)
            assertThat(http.get().uri(PATH).retrieve().body(String.class))
                    .contains("\"basePrice\":100.00")
                    .contains("\"finalPrice\":105.00");
            assertThat(item.get("currency").asString()).isEqualTo("EUR");
            assertThat(item.get("selectedProvider").asString()).isEqualTo("mock-alpha");
            assertThat(item.get("providers").toString()).isEqualTo("[\"mock-alpha\",\"mock-gamma\"]");
            assertThat(item.has("id")).isFalse();

            JsonNode segments = item.get("segments");
            assertThat(segments.size()).isEqualTo(2);
            assertThat(segments.get(0).get("flightNumber").asString()).isEqualTo("IB100");
            assertThat(segments.get(0).get("origin").asString()).isEqualTo("MAD");
            assertThat(segments.get(0).get("destination").asString()).isEqualTo("ZRH");
            assertThat(segments.get(0).get("departureAt").asString()).isEqualTo("2026-11-07T08:00:00Z");
            assertThat(segments.get(0).get("arrivalAt").asString()).isEqualTo("2026-11-07T10:00:00Z");
            assertThat(segments.get(1).get("origin").asString()).isEqualTo("ZRH");
            assertThat(segments.get(1).get("destination").asString()).isEqualTo("JFK");
            assertThat(segments.get(1).get("arrivalAt").asString()).isEqualTo("2026-11-07T13:00:00Z");

            JsonNode freshness = item.get("freshness");
            assertThat(freshness.get("status").asString()).isEqualTo("COMPLETE");
            assertThat(freshness.get("publishedAt").asString()).isEqualTo("2026-11-06T10:00:21Z");
            assertThat(freshness.get("missingProviders").size()).isZero();
            assertThat(freshness.get("staleSince").isNull()).isTrue();
        }

        @Test
        @DisplayName("AC-10: freshness is per item: complete, incomplete and stale days side by side")
        void freshnessPerItem() {
            rows.insertAll(List.of(
                    row("MAD", "JFK", "IB", "2026-11-07T10:00:00Z", "100.00").withKey("complete"),
                    row("MAD", "JFK", "IB", "2026-11-08T10:00:00Z", "200.00").withKey("incomplete"),
                    row("MAD", "JFK", "IB", "2026-11-09T10:00:00Z", "300.00").withKey("stale")));
            rows.completeDay(LocalDate.parse("2026-11-07"));
            rows.incompleteDay(LocalDate.parse("2026-11-08"), "mock-beta");
            rows.staleDay(LocalDate.parse("2026-11-09"), Instant.parse("2026-11-06T11:45:00Z"));
            rows.publish();

            JsonNode items = ok("").get("items");

            assertThat(items.get(0).get("freshness").get("status").asString()).isEqualTo("COMPLETE");
            assertThat(items.get(1).get("freshness").get("status").asString()).isEqualTo("INCOMPLETE");
            assertThat(items.get(1)
                            .get("freshness")
                            .get("missingProviders")
                            .get(0)
                            .asString())
                    .isEqualTo("mock-beta");
            assertThat(items.get(2).get("freshness").get("status").asString()).isEqualTo("STALE");
            assertThat(items.get(2).get("freshness").get("staleSince").asString())
                    .isEqualTo("2026-11-06T11:45:00Z");
        }
    }

    @Nested
    class Validation {

        @ParameterizedTest(name = "{0}")
        @CsvSource(
                delimiter = ';',
                value = {
                    "?origin=MA;                 origin",
                    "?origin=MADR;               origin",
                    "?destination=J1K;           destination",
                    "?carrier=IBE;               carrier",
                    "?carrier=I;                 carrier",
                    "?maxPrice=-1;               maxPrice",
                    "?maxPrice=abc;              maxPrice",
                    "?maxPrice=10.001;           maxPrice",
                    "?maxPrice=1000000000;       maxPrice",
                    "?limit=0;                   limit",
                    "?limit=101;                 limit",
                    "?limit=ten;                 limit",
                    "?date=2026-11-6;            date",
                    "?date=2026-11-04;           date",
                    "?date=2027-11-07;           date",
                    "?cursor=not-a-cursor!;      cursor",
                    "?cursor=djJ8MS4wMHwxfDAwMDAwMDAwMDAwMDAwMDA; cursor",
                })
        @DisplayName("AC-12..AC-16: invalid parameters answer 400 ProblemDetail naming the parameter")
        void badRequest(String query, String parameter) {
            ResponseEntity<JsonNode> response = get(PATH + query.trim());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            JsonNode problem = response.getBody();
            assertThat(problem.get("status").asInt()).isEqualTo(400);
            assertThat(problem.get("title").asString()).isEqualTo("Invalid request");
            assertThat(problem.get("detail").asString()).contains(parameter.trim());
            assertThat(problem.get("instance").asString()).isEqualTo(PATH);
        }

        @Test
        @DisplayName("AC-16: a cursor reused with different filters answers 400")
        void cursorWithOtherFilters() {
            IntStream.range(0, 3)
                    .forEach(i ->
                            rows.insert(row("MAD", "JFK", "IB", "2026-11-07T1" + i + ":00:00Z", "10" + i + ".00")));
            rows.completeDay(LocalDate.parse("2026-11-07"));
            rows.publish();
            String cursor = ok("?origin=MAD&limit=1").get("nextCursor").asString();

            assertThat(get(PATH + "?origin=MAD&limit=2&cursor=" + cursor).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            ResponseEntity<JsonNode> other = get(PATH + "?origin=BCN&limit=1&cursor=" + cursor);
            ResponseEntity<JsonNode> none = get(PATH + "?limit=1&cursor=" + cursor);

            assertThat(other.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(other.getBody().get("detail").asString()).contains("cursor");
            assertThat(none.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
