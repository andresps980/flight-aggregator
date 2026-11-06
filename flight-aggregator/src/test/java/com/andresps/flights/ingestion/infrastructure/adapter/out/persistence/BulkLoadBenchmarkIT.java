package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.complete;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.itineraries;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.aggregation.domain.deduplication.AggregatedItinerary;
import com.andresps.flights.support.StoredData;
import com.andresps.flights.support.TestPostgres;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-0018 measurement: 1,000,000 rows as 7 day snapshots (≈ 143,000 each, like a 1M ingestion), loaded with each
 * technique into the shared PostgreSQL container, the second time over existing days (delete + load, as every run
 * after the first). Tagged {@code slow}: {@code ./mvnw -pl flight-aggregator verify -Pslow-tests
 * -Dit.test=BulkLoadBenchmarkIT}.
 */
@Tag("slow")
class BulkLoadBenchmarkIT {

    private static final Logger LOG = LoggerFactory.getLogger(BulkLoadBenchmarkIT.class);
    private static final int DAYS = 7;
    private static final int PER_DAY = 1_000_000 / DAYS + 1;
    private static final LocalDate FIRST_DAY = LocalDate.parse("2026-11-06");

    private final DataSource dataSource = TestPostgres.dataSource();

    @BeforeEach
    void emptyTables() {
        TestPostgres.truncateAll(dataSource);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(
            "com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.PostgresDaySnapshotStoreIT#loaders")
    @DisplayName("ADR-0018: 1,000,000 rows load well under a minute (first load and replacement)")
    void loadOneMillionRows(ItineraryBulkLoader loader) {
        PostgresDaySnapshotStore store = new PostgresDaySnapshotStore(dataSource, loader);
        List<List<AggregatedItinerary>> days = IntStream.range(0, DAYS)
                .mapToObj(d -> itineraries(FIRST_DAY.plusDays(d), PER_DAY))
                .toList();

        Duration firstLoad = time(() -> loadAll(store, days));
        Duration replacement = time(() -> loadAll(store, days));

        LOG.info(
                "Bulk load {}: {} rows, first load {} ms, replacement {} ms",
                loader,
                DAYS * PER_DAY,
                firstLoad.toMillis(),
                replacement.toMillis());
        assertThat(new StoredData(dataSource).rows()).isEqualTo((long) DAYS * PER_DAY);
        assertThat(firstLoad).isLessThan(Duration.ofSeconds(60));
        assertThat(replacement).isLessThan(Duration.ofSeconds(60));
    }

    private static void loadAll(PostgresDaySnapshotStore store, List<List<AggregatedItinerary>> days) {
        UUID run = UUID.randomUUID();
        for (int d = 0; d < days.size(); d++) {
            store.replace(complete(FIRST_DAY.plusDays(d), run, days.get(d)));
        }
    }

    private static Duration time(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return Duration.ofNanos(System.nanoTime() - start);
    }
}
