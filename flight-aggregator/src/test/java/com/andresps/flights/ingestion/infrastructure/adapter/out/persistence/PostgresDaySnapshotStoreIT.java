package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.ALPHA;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.BETA;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.GAMMA;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.complete;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.itineraries;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.itinerary;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.providers;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.aggregation.domain.deduplication.AggregatedItinerary;
import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.model.Segment;
import com.andresps.flights.ingestion.domain.DaySnapshot;
import com.andresps.flights.support.StoredData;
import com.andresps.flights.support.TestPostgres;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Spec 004: the PostgreSQL snapshot store against a real database (shared Testcontainers PostgreSQL). */
class PostgresDaySnapshotStoreIT {

    private static final LocalDate DAY = LocalDate.parse("2026-11-06");
    private static final LocalDate OTHER_DAY = LocalDate.parse("2026-11-07");
    private static final UUID RUN_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID RUN_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DataSource dataSource = TestPostgres.dataSource();
    private final JdbcClient jdbc = JdbcClient.create(dataSource);
    private final StoredData stored = new StoredData(dataSource);
    private final PostgresDaySnapshotStore store = new PostgresDaySnapshotStore(dataSource, copyLoader());

    static ItineraryBulkLoader copyLoader() {
        return new CopyItineraryLoader(new ItineraryCopyEncoder(64 * 1024));
    }

    static Stream<ItineraryBulkLoader> loaders() {
        return Stream.of(copyLoader(), new BatchInsertItineraryLoader(1000));
    }

    @BeforeEach
    void emptyTables() {
        TestPostgres.truncateAll(dataSource);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("loaders")
    @DisplayName("AC-10: every field round-trips exactly (instants, prices at scale 2, providers, segments in order)")
    void roundTrip(ItineraryBulkLoader loader) {
        AggregatedItinerary connecting = itinerary(DAY, 0);
        AggregatedItinerary direct = itinerary(DAY, 1);
        AggregatedItinerary expensive = withPrices(itinerary(DAY, 2), "999999999.99", "1049999999.99");

        new PostgresDaySnapshotStore(dataSource, loader)
                .replace(complete(DAY, RUN_A, List.of(connecting, direct, expensive)));

        for (AggregatedItinerary expected : List.of(connecting, direct, expensive)) {
            assertRow(expected, RUN_A);
        }
        assertThat(stored.rows(DAY)).isEqualTo(3);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("loaders")
    @DisplayName("AC-11: hostile text (tab, newline, quote, backslash, braces, comma) is stored exactly")
    void hostileTextRoundTrips(ItineraryBulkLoader loader) {
        ProviderId hostile = new ProviderId("evil\t\"x\",{y}\\z\nnext\rline NULL \\N");
        AggregatedItinerary base = itinerary(DAY, 1);
        AggregatedItinerary row = new AggregatedItinerary(
                base.key(),
                new FlightOffer(base.itinerary(), hostile, base.basePrice()),
                base.finalPrice(),
                new TreeSet<>(List.of(hostile, ALPHA)));

        new PostgresDaySnapshotStore(dataSource, loader).replace(complete(DAY, RUN_A, List.of(row, itinerary(DAY, 2))));

        assertThat(stored.rows(DAY)).isEqualTo(2);
        assertRow(row, RUN_A);
    }

    @Test
    @DisplayName("AC-2: replacing a day is atomic: a concurrent reader sees the old or the new snapshot, never a mix")
    void replacementIsAtomic() throws InterruptedException {
        store.replace(complete(DAY, RUN_A, itineraries(DAY, 5_000)));
        store.replace(complete(OTHER_DAY, RUN_A, itineraries(OTHER_DAY, 100)));
        var observations = new ConcurrentLinkedQueue<String>();
        var stop = new AtomicBoolean();
        Thread reader = Thread.ofVirtual().start(() -> {
            while (!stop.get()) {
                observations.add(
                        jdbc.sql("SELECT count(*) || '/' || string_agg(DISTINCT run_id::text, ',') FROM itinerary"
                                        + " WHERE departure_date = ?")
                                .param(DAY)
                                .query(String.class)
                                .single());
            }
        });

        store.replace(complete(DAY, RUN_B, itineraries(DAY, 20_000)));
        stop.set(true);
        reader.join();

        assertThat(observations)
                .isNotEmpty()
                .allSatisfy(seen -> assertThat(seen).isIn("5000/" + RUN_A, "20000/" + RUN_B));
        assertThat(stored.runIds(DAY)).containsExactly(RUN_B);
        assertThat(stored.rows(OTHER_DAY)).as("other days untouched").isEqualTo(100);
        StoredData.DayRow snapshot = stored.snapshot(DAY).orElseThrow();
        assertThat(snapshot.runId()).isEqualTo(RUN_B);
        assertThat(snapshot.lastRunId()).isEqualTo(RUN_B);
        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.itineraries()).isEqualTo(20_000);
        assertThat(snapshot.staleSince()).isNull();
        assertThat(snapshot.providers()).containsExactly("mock-alpha", "mock-beta", "mock-gamma");
        assertThat(snapshot.missingProviders()).isEmpty();
    }

    @Test
    @DisplayName("AC-4: a partial snapshot is recorded incomplete with its missing providers")
    void incompleteSnapshot() {
        store.replace(write(DAY, RUN_A, itineraries(DAY, 10), providers(BETA)));

        StoredData.DayRow snapshot = stored.snapshot(DAY).orElseThrow();
        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.providers()).containsExactly("mock-alpha", "mock-gamma");
        assertThat(snapshot.missingProviders()).containsExactly("mock-beta");
        assertThat(store.find(DAY)).hasValueSatisfying(found -> {
            assertThat(found.providers()).containsExactly(ALPHA, GAMMA);
            assertThat(found.runId()).isEqualTo(RUN_A);
            assertThat(found.staleSince()).isNull();
        });
    }

    @Test
    @DisplayName("AC-3/AC-6: marking a day stale keeps its rows; the first stale instant is kept across runs")
    void markStale() {
        store.replace(complete(DAY, RUN_A, itineraries(DAY, 10)));
        Instant first = Instant.parse("2026-11-05T12:15:00Z");

        store.markStale(DAY, RUN_B, first);
        store.markStale(DAY, UUID.randomUUID(), first.plusSeconds(900));

        DaySnapshot snapshot = store.find(DAY).orElseThrow();
        assertThat(snapshot.staleSince()).isEqualTo(first);
        assertThat(snapshot.runId()).isEqualTo(RUN_A);
        assertThat(stored.rows(DAY)).isEqualTo(10);
        assertThat(stored.runIds(DAY)).containsExactly(RUN_A);
    }

    @Test
    @DisplayName("AC-8 (error): a write failing mid-load rolls back the whole day; the previous snapshot is intact")
    void failedWriteRollsBack() {
        store.replace(complete(DAY, RUN_A, itineraries(DAY, 50)));
        List<AggregatedItinerary> withDuplicate = new ArrayList<>(itineraries(DAY, 200));
        withDuplicate.add(itinerary(DAY, 7)); // same key twice: unique violation at the end of the load

        assertThatThrownBy(() -> store.replace(complete(DAY, RUN_B, withDuplicate)))
                .isInstanceOf(RuntimeException.class);

        assertThat(stored.rows(DAY)).isEqualTo(50);
        assertThat(stored.runIds(DAY)).containsExactly(RUN_A);
        assertThat(stored.snapshot(DAY).orElseThrow().runId()).isEqualTo(RUN_A);
    }

    @Test
    @DisplayName("AC-12: days before a date are deleted with their snapshot status; that day and later are kept")
    void deleteDaysBefore() {
        store.replace(complete(DAY, RUN_A, itineraries(DAY, 5)));
        store.replace(complete(OTHER_DAY, RUN_A, itineraries(OTHER_DAY, 5)));

        store.deleteDaysBefore(OTHER_DAY);

        assertThat(stored.days()).containsExactly(OTHER_DAY);
        assertThat(stored.snapshot(DAY)).isEmpty();
        assertThat(stored.snapshot(OTHER_DAY)).isPresent();
    }

    @Test
    @DisplayName("AC-13: refreshing statistics analyzes the itinerary table")
    void refreshStatistics() {
        Instant before =
                jdbc.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();

        store.refreshStatistics();

        assertThat(stored.lastAnalyze()).hasValueSatisfying(at -> assertThat(at).isAfterOrEqualTo(before));
    }

    private void assertRow(AggregatedItinerary expected, UUID runId) {
        Map<String, Object> row = jdbc.sql("SELECT * FROM itinerary WHERE itinerary_key = ?")
                .param(expected.key().value())
                .query()
                .singleRow();
        List<Segment> segments = expected.itinerary().segments();
        assertThat(row.get("departure_date")).isEqualTo(java.sql.Date.valueOf(DAY));
        assertThat(row.get("origin")).isEqualTo(segments.getFirst().origin().value());
        assertThat(row.get("destination"))
                .isEqualTo(segments.getLast().destination().value());
        assertThat(((java.sql.Timestamp) row.get("departure_at")).toInstant())
                .isEqualTo(segments.getFirst().departure());
        assertThat(((java.sql.Timestamp) row.get("arrival_at")).toInstant())
                .isEqualTo(segments.getLast().arrival());
        assertThat(row.get("airline")).isEqualTo(segments.getFirst().airline().value());
        assertThat(((Number) row.get("stops")).intValue()).isEqualTo(segments.size() - 1);
        assertThat((BigDecimal) row.get("base_price"))
                .isEqualTo(expected.basePrice().amount());
        assertThat((BigDecimal) row.get("final_price"))
                .isEqualTo(expected.finalPrice().amount());
        assertThat(row.get("selected_provider"))
                .isEqualTo(expected.selectedOffer().provider().value());
        assertThat(providersOf(row.get("providers")))
                .containsExactlyElementsOf(
                        expected.providers().stream().map(ProviderId::value).toList());
        assertThat(row.get("run_id")).isEqualTo(runId);
        assertThat(row.get("id")).isNotNull();
        List<Map<String, String>> storedSegments =
                segmentsOf(row.get("segments").toString());
        assertThat(storedSegments).hasSize(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            assertThat(storedSegments.get(i))
                    .containsEntry("flightNumber", segment.flightNumber().toString())
                    .containsEntry("origin", segment.origin().value())
                    .containsEntry("destination", segment.destination().value())
                    .containsEntry("departureAt", segment.departure().toString())
                    .containsEntry("arrivalAt", segment.arrival().toString());
        }
    }

    private static List<String> providersOf(Object array) {
        try {
            return Arrays.asList((String[]) ((java.sql.Array) array).getArray());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> segmentsOf(String json) {
        return JSON.readValue(json, List.class);
    }

    private static AggregatedItinerary withPrices(AggregatedItinerary base, String basePrice, String finalPrice) {
        return new AggregatedItinerary(
                base.key(),
                new FlightOffer(
                        base.itinerary(),
                        base.selectedOffer().provider(),
                        com.andresps.flights.aggregation.domain.model.Money.eur(basePrice)),
                com.andresps.flights.aggregation.domain.model.Money.eur(finalPrice),
                base.providers());
    }
}
