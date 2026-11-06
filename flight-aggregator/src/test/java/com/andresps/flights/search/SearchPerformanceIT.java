package com.andresps.flights.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.PostgresDaySnapshotStore;
import com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures;
import com.andresps.flights.search.domain.PagePosition;
import com.andresps.flights.search.domain.SearchCriteria;
import com.andresps.flights.search.infrastructure.adapter.out.persistence.ItinerarySearchSql;
import com.andresps.flights.support.TestPostgres;
import com.andresps.flights.support.TestRedis;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/**
 * Spec 005 AC-22 / AC-23 on 1,000,000 gds-mock-shaped rows ({@link SearchVolume}), written through the real ingestion
 * store ({@code COPY}). One long test, in order:
 *
 * <ol>
 *   <li>{@code COPY} time of 1M rows without, then with, the search indexes (ADR-0020);
 *   <li>{@code EXPLAIN (ANALYZE, BUFFERS)} of the search query per filter combination: never a sequential scan, and no
 *       sort where the matching set is large;
 *   <li>keyset vs {@code OFFSET}, first page and page 1,000 (or the deepest page a filter has), server side;
 *   <li>cached vs uncached latency through the HTTP API, and the uncached catalog.
 * </ol>
 *
 * Results go to the log and to {@code target/search-performance.md} (copied into SOLUTION.md and ADR-0020). Run with
 * {@code ./mvnw -pl flight-aggregator verify -Pslow-tests -Dit.test=SearchPerformanceIT}.
 */
@Tag("slow")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestPostgres.class, TestRedis.class, SearchSpringTest.SearchTime.class})
class SearchPerformanceIT {

    private static final Logger LOG = LoggerFactory.getLogger(SearchPerformanceIT.class);
    private static final LocalDate FIRST_DAY = LocalDate.parse("2026-11-06");
    private static final int DAYS = 7;
    private static final int PER_DAY = 1_000_000 / DAYS + 1;
    /** Every day is in the future, the first one starting an hour later. */
    private static final Instant NOW = Instant.parse("2026-11-05T23:00:00Z");

    private static final int PAGE = 20;
    private static final int DEEP_PAGE = 1_000;
    private static final int WARMUP = 5;
    private static final int RUNS = 30;
    private static final Path REPORT = Path.of("target", "search-performance.md");

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PostgresDaySnapshotStore store;

    private final StringBuilder report = new StringBuilder("# Search performance at 1M rows (spec 005)\n\n");

    private record Combination(String name, String query, SearchCriteria criteria, boolean largeSet) {}

    private record Stats(double medianMs, double p95Ms) {
        @Override
        public String toString() {
            return String.format("%.2f / %.2f", medianMs, p95Ms);
        }
    }

    @Test
    @DisplayName("AC-22 / AC-23: indexes, plans, keyset vs offset and cache latency on 1M rows")
    void measure() throws IOException {
        SearchSpringTest.CLOCK.set(NOW);
        JdbcClient jdbc = JdbcClient.create(dataSource);

        measureBulkLoad(jdbc);
        List<Combination> combinations = combinations(jdbc);
        checkPlans(jdbc, combinations);
        compareKeysetAndOffset(jdbc, combinations);
        compareCachedAndUncached(jdbc, combinations);

        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report);
        LOG.info("\n{}", report);
    }

    /** ADR-0020: the search indexes' cost on COPY, measured by loading 1M rows without and with them. */
    private void measureBulkLoad(JdbcClient jdbc) {
        List<String> searchIndexes = jdbc.sql("""
                        SELECT indexdef FROM pg_indexes
                        WHERE tablename = 'itinerary' AND indexname NOT IN
                            ('itinerary_pkey', 'itinerary_key_unique', 'itinerary_departure_date_idx')""").query(String.class).list();
        List<String> names = jdbc.sql("""
                        SELECT indexname FROM pg_indexes
                        WHERE tablename = 'itinerary' AND indexname NOT IN
                            ('itinerary_pkey', 'itinerary_key_unique', 'itinerary_departure_date_idx')""").query(String.class).list();
        assertThat(searchIndexes).as("the search indexes of ADR-0020").isNotEmpty();

        TestPostgres.truncateAll(dataSource);
        names.forEach(name -> jdbc.sql("DROP INDEX " + name).update());
        Duration without = time(this::loadAll);
        TestPostgres.truncateAll(dataSource);
        searchIndexes.forEach(def -> jdbc.sql(def).update());
        Duration with = time(this::loadAll);
        Duration replacement = time(this::loadAll);
        jdbc.sql("VACUUM ANALYZE itinerary").update();

        report.append("## Bulk load (COPY, 1M rows, 7 days)\n\n")
                .append("| Indexes | First load | Replacement |\n|---|---:|---:|\n")
                .append(String.format("| PK, unique key, departure_date | %.1f s | |%n", without.toMillis() / 1000.0))
                .append(String.format(
                        "| + search indexes (%s) | %.1f s | %.1f s |%n%n",
                        String.join(", ", names), with.toMillis() / 1000.0, replacement.toMillis() / 1000.0))
                .append("Search index definitions:\n\n```sql\n")
                .append(String.join(";\n", searchIndexes))
                .append(";\n```\n\n");
        assertThat(with).isLessThan(Duration.ofSeconds(60));
    }

    private void loadAll() {
        UUID run = UUID.randomUUID();
        for (int d = 0; d < DAYS; d++) {
            LocalDate day = FIRST_DAY.plusDays(d);
            store.replace(SnapshotFixtures.complete(day, run, SearchVolume.day(day, PER_DAY)));
        }
    }

    /** The common combinations of AC-22, with the busiest route, origin and carrier of the loaded data. */
    private List<Combination> combinations(JdbcClient jdbc) {
        Map<String, Object> route = jdbc.sql("""
                        SELECT origin, destination, count(*) AS n FROM itinerary
                        GROUP BY origin, destination ORDER BY n DESC LIMIT 1""").query().singleRow();
        String o = route.get("origin").toString().trim();
        String d = route.get("destination").toString().trim();
        String c = jdbc.sql("SELECT airline FROM itinerary GROUP BY airline ORDER BY count(*) DESC LIMIT 1")
                .query(String.class)
                .single()
                .trim();
        String day = FIRST_DAY.plusDays(2).toString();
        String max = "200"; // below the median final price
        String aboveAll = "999999";
        report.append(String.format("Busiest route %s-%s: %s rows; busiest carrier %s.%n%n", o, d, route.get("n"), c));
        return List.of(
                combination("none", "", null, null, null, null, null, true),
                combination("maxPrice", "&maxPrice=" + max, null, null, null, max, null, true),
                combination("date", "&date=" + day, null, null, day, null, null, true),
                combination("date + maxPrice", "&date=" + day + "&maxPrice=" + max, null, null, day, max, null, true),
                combination("carrier", "&carrier=" + c, null, null, null, null, c, true),
                combination("origin", "&origin=" + o, o, null, null, null, null, true),
                combination(
                        "origin + carrier + maxPrice",
                        "&origin=" + o + "&carrier=" + c + "&maxPrice=" + max,
                        o,
                        null,
                        null,
                        max,
                        c,
                        false),
                combination("route", "&origin=" + o + "&destination=" + d, o, d, null, null, null, false),
                combination(
                        "route + date",
                        "&origin=" + o + "&destination=" + d + "&date=" + day,
                        o,
                        d,
                        day,
                        null,
                        null,
                        false),
                combination(
                        "route + carrier",
                        "&origin=" + o + "&destination=" + d + "&carrier=" + c,
                        o,
                        d,
                        null,
                        null,
                        c,
                        false),
                combination(
                        "route, maxPrice above all (worst realistic)",
                        "&origin=" + o + "&destination=" + d + "&maxPrice=" + aboveAll,
                        o,
                        d,
                        null,
                        aboveAll,
                        null,
                        false));
    }

    private static Combination combination(
            String name, String query, String o, String d, String date, String max, String c, boolean largeSet) {
        SearchCriteria criteria = SearchCriteria.parse(o, d, date, max, c, LocalDate.parse("2026-11-05"));
        return new Combination(name, query, criteria, largeSet);
    }

    /** AC-22: never a sequential scan; an in-memory sort only where the matching set is small (a route). */
    private void checkPlans(JdbcClient jdbc, List<Combination> combinations) {
        report.append("## Plans (EXPLAIN (ANALYZE, BUFFERS), first page of 20)\n\n");
        for (Combination combination : combinations) {
            ItinerarySearchSql.SqlQuery query = ItinerarySearchSql.page(combination.criteria(), null, PAGE + 1, NOW);
            String plan = String.join(
                    "\n",
                    jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + query.sql())
                            .params(query.params())
                            .query(String.class)
                            .list());
            report.append("### ")
                    .append(combination.name())
                    .append("\n\n```\n")
                    .append(plan)
                    .append("\n```\n\n");

            assertThat(plan).as(combination.name()).doesNotContain("Seq Scan on itinerary");
            if (combination.largeSet()) {
                assertThat(plan)
                        .as(combination.name() + ": large set, keyset order from the index")
                        .doesNotContain("Sort Key");
            }
        }
    }

    /** AC-23: first page and page 1,000 (or the deepest), keyset vs the equivalent OFFSET query. */
    private void compareKeysetAndOffset(JdbcClient jdbc, List<Combination> combinations) {
        report.append("## Keyset vs OFFSET, server side (ms, median / p95 of ")
                .append(RUNS)
                .append(" runs)\n\n| Filters | Matching rows | Page | Keyset | OFFSET |\n|---|---:|---:|---:|---:|\n");
        for (Combination combination : combinations) {
            ItinerarySearchSql.SqlQuery first = ItinerarySearchSql.page(combination.criteria(), null, PAGE + 1, NOW);
            long matching = jdbc.sql("SELECT count(*) FROM (" + first.sql().replaceFirst("LIMIT \\?$", "") + ") m")
                    .params(first.params().subList(0, first.params().size() - 1))
                    .query(Long.class)
                    .single();
            int deepest = (int) Math.min(DEEP_PAGE, Math.max(1, (matching - 1) / PAGE + 1));
            for (int page : deepest > 1 ? List.of(1, deepest) : List.of(1)) {
                int offset = (page - 1) * PAGE;
                PagePosition after = page == 1 ? null : positionAt(jdbc, first, offset - 1);
                ItinerarySearchSql.SqlQuery keyset =
                        ItinerarySearchSql.page(combination.criteria(), after, PAGE + 1, NOW);
                Stats keysetStats = timeQuery(jdbc, keyset.sql(), keyset.params());
                Stats offsetStats = timeQuery(jdbc, first.sql() + " OFFSET " + offset, first.params());
                report.append(String.format(
                        "| %s | %,d | %,d | %s | %s |%n",
                        combination.name(), matching, page, keysetStats, offsetStats));
                if (page == DEEP_PAGE && combination.name().equals("none")) {
                    assertThat(keysetStats.medianMs()).isLessThan(offsetStats.medianMs());
                }
                assertThat(keysetStats.medianMs())
                        .as(combination.name() + " page " + page)
                        .isLessThan(100);
            }
        }
        report.append('\n');
    }

    private static PagePosition positionAt(JdbcClient jdbc, ItinerarySearchSql.SqlQuery first, int rowOffset) {
        String sql = "SELECT final_price, id FROM (" + first.sql().replaceFirst("LIMIT \\?$", "") + ") m "
                + "ORDER BY final_price, id OFFSET " + rowOffset + " LIMIT 1";
        return jdbc.sql(sql)
                .params(first.params().subList(0, first.params().size() - 1))
                .query((rs, n) -> new PagePosition(rs.getBigDecimal(1), rs.getLong(2)))
                .single();
    }

    private static Stats timeQuery(JdbcClient jdbc, String sql, List<Object> params) {
        return stats(() -> jdbc.sql(sql).params(params).query().listOfRows().size());
    }

    /** AC-23: through the API, uncached (right after a data version bump) vs cached (the same request again). */
    private void compareCachedAndUncached(JdbcClient jdbc, List<Combination> combinations) {
        RestClient http = RestClient.create("http://localhost:" + port);
        report.append("## API latency, uncached vs cached (ms, median / p95 of ")
                .append(RUNS)
                .append(" runs, client side)\n\n| Request | Uncached | Cached |\n|---|---:|---:|\n");
        List<String> requests = new ArrayList<>();
        combinations.forEach(c -> requests.add("/api/v1/itineraries?limit=" + PAGE + c.query()));
        requests.add("/api/v1/catalog");
        for (String request : requests) {
            List<Double> uncached = new ArrayList<>();
            List<Double> cached = new ArrayList<>();
            for (int i = 0; i < WARMUP + RUNS; i++) {
                jdbc.sql("UPDATE data_version SET version = version + 1").update();
                double miss = millis(() -> call(http, request));
                double hit = millis(() -> call(http, request));
                if (i >= WARMUP) {
                    uncached.add(miss);
                    cached.add(hit);
                }
            }
            report.append(String.format("| `%s` | %s | %s |%n", request, summarize(uncached), summarize(cached)));
        }
        report.append('\n');
    }

    private static int call(RestClient http, String request) {
        var response = http.get().uri(request).retrieve().toEntity(String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().length();
    }

    private static Stats stats(Supplier<Integer> action) {
        List<Double> samples = new ArrayList<>();
        for (int i = 0; i < WARMUP + RUNS; i++) {
            double ms = millis(action);
            if (i >= WARMUP) {
                samples.add(ms);
            }
        }
        return summarize(samples);
    }

    private static Stats summarize(List<Double> samples) {
        List<Double> sorted = samples.stream().sorted().toList();
        return new Stats(sorted.get(sorted.size() / 2), sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1));
    }

    private static double millis(Supplier<Integer> action) {
        long start = System.nanoTime();
        action.get();
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static Duration time(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return Duration.ofNanos(System.nanoTime() - start);
    }
}
