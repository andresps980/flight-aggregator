package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.ALL;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.BETA;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.itineraries;
import static com.andresps.flights.ingestion.infrastructure.adapter.out.persistence.SnapshotFixtures.itinerary;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.aggregation.domain.deduplication.AggregatedItinerary;
import com.andresps.flights.aggregation.domain.deduplication.AggregationResult;
import com.andresps.flights.aggregation.domain.deduplication.AggregationStatistics;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.ingestion.application.service.DaySnapshotPublisher;
import com.andresps.flights.ingestion.domain.DayAggregation;
import com.andresps.flights.ingestion.domain.DayOutcome;
import com.andresps.flights.ingestion.domain.DayPublication;
import com.andresps.flights.ingestion.domain.SnapshotPolicy;
import com.andresps.flights.support.MutableClock;
import com.andresps.flights.support.StoredData;
import com.andresps.flights.support.TestPostgres;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spec 004 / ADR-0017: day snapshot semantics end to end on PostgreSQL: the publisher's decisions applied by the real
 * store.
 */
class DaySnapshotPersistenceIT {

    private static final LocalDate DAY = LocalDate.parse("2026-11-06");
    private static final UUID RUN_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID RUN_3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private final DataSource dataSource = TestPostgres.dataSource();
    private final StoredData stored = new StoredData(dataSource);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-05T12:00:00Z"));
    private final DaySnapshotPublisher publisher = new DaySnapshotPublisher(
            new PostgresDaySnapshotStore(dataSource, PostgresDaySnapshotStoreIT.copyLoader()),
            new SnapshotPolicy(Duration.ofHours(2)),
            clock);

    @BeforeEach
    void emptyTables() {
        TestPostgres.truncateAll(dataSource);
    }

    private static DayAggregation day(UUID run, List<AggregatedItinerary> itineraries, ProviderId... missing) {
        SortedSet<ProviderId> missingSet = new TreeSet<>(List.of(missing));
        SortedSet<ProviderId> contributing = new TreeSet<>(ALL);
        contributing.removeAll(missingSet);
        return new DayAggregation(
                run, DAY, new AggregationResult(itineraries, AggregationStatistics.empty()), contributing, missingSet);
    }

    @Test
    @DisplayName("spec 004 AC-3: a partial day keeps the previous complete snapshot and marks it stale")
    void partialDayKeepsPreviousData() {
        publisher.publish(day(RUN_1, itineraries(DAY, 30)));
        clock.advance(Duration.ofMinutes(15));

        DayPublication publication = publisher.publish(day(RUN_2, itineraries(DAY, 10), BETA));

        assertThat(publication.outcome()).isEqualTo(DayOutcome.KEPT_STALE);
        assertThat(stored.rows(DAY)).isEqualTo(30);
        assertThat(stored.runIds(DAY)).containsExactly(RUN_1);
        StoredData.DayRow snapshot = stored.snapshot(DAY).orElseThrow();
        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.staleSince()).isEqualTo(clock.instant());
        assertThat(snapshot.lastRunId()).isEqualTo(RUN_2);
    }

    @Test
    @DisplayName("spec 004 AC-4: a partial day without previous data is published as incomplete")
    void partialDayWithoutPreviousDataIsPublished() {
        DayPublication publication = publisher.publish(day(RUN_1, itineraries(DAY, 10), BETA));

        assertThat(publication).isEqualTo(DayPublication.publishedIncomplete(10, 0));
        assertThat(stored.rows(DAY)).isEqualTo(10);
        StoredData.DayRow snapshot = stored.snapshot(DAY).orElseThrow();
        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.missingProviders()).containsExactly("mock-beta");
        assertThat(snapshot.staleSince()).isNull();
    }

    @Test
    @DisplayName("spec 004 AC-2/AC-3: the next complete day replaces a stale snapshot and clears the stale mark")
    void completeDayClearsStale() {
        publisher.publish(day(RUN_1, itineraries(DAY, 30)));
        publisher.publish(day(RUN_2, itineraries(DAY, 10), BETA));

        DayPublication publication = publisher.publish(day(RUN_3, itineraries(DAY, 12)));

        assertThat(publication).isEqualTo(DayPublication.replaced(12, 0));
        assertThat(stored.runIds(DAY)).containsExactly(RUN_3);
        assertThat(stored.snapshot(DAY).orElseThrow().staleSince()).isNull();
    }

    @Test
    @DisplayName("spec 004 AC-5: a snapshot stale for longer than max-stale-age is replaced by the partial day")
    void staleTooLongIsReplaced() {
        publisher.publish(day(RUN_1, itineraries(DAY, 30)));
        publisher.publish(day(RUN_2, itineraries(DAY, 10), BETA));
        clock.advance(Duration.ofHours(2));

        DayPublication publication = publisher.publish(day(RUN_3, itineraries(DAY, 10), BETA));

        assertThat(publication).isEqualTo(DayPublication.publishedIncomplete(10, 0));
        assertThat(stored.runIds(DAY)).containsExactly(RUN_3);
        assertThat(stored.snapshot(DAY).orElseThrow().complete()).isFalse();
    }

    @Test
    @DisplayName("spec 004 AC-6: a day where every provider failed keeps its snapshot, marked stale")
    void unavailableDayKeepsSnapshot() {
        publisher.publish(day(RUN_1, itineraries(DAY, 30)));

        assertThat(publisher.unavailable(RUN_2, DAY)).isEqualTo(DayPublication.keptStale());
        assertThat(publisher.unavailable(RUN_2, DAY.plusDays(1))).isEqualTo(DayPublication.noData());

        assertThat(stored.rows(DAY)).isEqualTo(30);
        assertThat(stored.snapshot(DAY).orElseThrow().staleSince()).isNotNull();
        assertThat(stored.snapshot(DAY.plusDays(1))).isEmpty();
    }

    @Test
    @DisplayName("spec 004 AC-9: misfiled itineraries are not stored; stored + misfiled = itineraries produced")
    void misfiledAreNotStored() {
        List<AggregatedItinerary> produced = new ArrayList<>(itineraries(DAY, 20));
        produced.add(itinerary(DAY.plusDays(1), 0));

        DayPublication publication = publisher.publish(day(RUN_1, produced));

        assertThat(publication).isEqualTo(DayPublication.replaced(20, 1));
        assertThat(stored.rows()).isEqualTo(20);
        assertThat(stored.days()).containsExactly(DAY);
    }
}
