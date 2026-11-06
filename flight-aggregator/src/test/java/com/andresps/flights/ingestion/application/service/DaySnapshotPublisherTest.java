package com.andresps.flights.ingestion.application.service;

import static com.andresps.flights.aggregation.domain.FlightFixtures.ALPHA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.BETA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.GAMMA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.itinerary;
import static com.andresps.flights.aggregation.domain.FlightFixtures.offer;
import static com.andresps.flights.aggregation.domain.FlightFixtures.segment;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.aggregation.domain.deduplication.AggregatedItinerary;
import com.andresps.flights.aggregation.domain.deduplication.AggregationResult;
import com.andresps.flights.aggregation.domain.deduplication.AggregationStatistics;
import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.Money;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.ingestion.domain.DayAggregation;
import com.andresps.flights.ingestion.domain.DayOutcome;
import com.andresps.flights.ingestion.domain.DayPublication;
import com.andresps.flights.ingestion.domain.DaySnapshot;
import com.andresps.flights.ingestion.domain.SnapshotPolicy;
import com.andresps.flights.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec 004: what publishing a day does to the stored snapshot, with an in-memory store. */
class DaySnapshotPublisherTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-06");
    private static final UUID PREVIOUS_RUN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-05T12:00:00Z"));
    private final InMemoryDaySnapshotStore store = new InMemoryDaySnapshotStore();
    private final DaySnapshotPublisher publisher =
            new DaySnapshotPublisher(store, new SnapshotPolicy(Duration.ofHours(2)), clock);

    private static AggregatedItinerary aggregated(String flight, LocalDate day, String hour) {
        FlightOffer offer =
                offer(ALPHA, "100.00", itinerary(segment(flight, "MAD", "BCN", day + "T" + hour, day + "T23:30")));
        return new AggregatedItinerary(
                offer.itinerary().key(), offer, Money.eur("105.00"), new TreeSet<>(Set.of(ALPHA)));
    }

    private static DayAggregation day(List<AggregatedItinerary> itineraries, ProviderId... missing) {
        TreeSet<ProviderId> missingSet = new TreeSet<>(Set.of(missing));
        TreeSet<ProviderId> contributing = new TreeSet<>(Set.of(ALPHA, BETA, GAMMA));
        contributing.removeAll(missingSet);
        return new DayAggregation(
                RUN, DAY, new AggregationResult(itineraries, AggregationStatistics.empty()), contributing, missingSet);
    }

    private void seedPrevious(Instant staleSince, ProviderId... providers) {
        store.seed(
                new DaySnapshot(
                        DAY,
                        PREVIOUS_RUN,
                        new TreeSet<>(Set.of(providers)),
                        clock.instant().minusSeconds(900),
                        staleSince),
                List.of(aggregated("IB9", DAY, "07:00")));
    }

    @Test
    @DisplayName("AC-2: a complete day replaces the previous snapshot and is recorded complete and fresh")
    void completeDayReplaces() {
        seedPrevious(clock.instant().minusSeconds(60), ALPHA, BETA, GAMMA);
        List<AggregatedItinerary> fresh = List.of(aggregated("IB1", DAY, "08:00"), aggregated("IB2", DAY, "09:00"));

        DayPublication publication = publisher.publish(day(fresh));

        assertThat(publication).isEqualTo(DayPublication.replaced(2, 0));
        assertThat(store.rows(DAY)).containsExactlyElementsOf(fresh);
        DaySnapshot snapshot = store.find(DAY).orElseThrow();
        assertThat(snapshot.runId()).isEqualTo(RUN);
        assertThat(snapshot.staleSince()).isNull();
        assertThat(snapshot.providers()).containsExactly(ALPHA, BETA, GAMMA);
    }

    @Test
    @DisplayName("AC-3: a day missing a provider of the existing snapshot keeps the old rows and marks them stale")
    void partialDayKeepsPreviousSnapshot() {
        seedPrevious(null, ALPHA, BETA, GAMMA);

        DayPublication publication = publisher.publish(day(List.of(aggregated("IB1", DAY, "08:00")), BETA));

        assertThat(publication).isEqualTo(DayPublication.keptStale());
        assertThat(store.rows(DAY))
                .extracting(a -> a.key().value())
                .singleElement()
                .asString()
                .contains("IB9");
        assertThat(store.find(DAY).orElseThrow().staleSince()).isEqualTo(clock.instant());
        assertThat(store.find(DAY).orElseThrow().runId()).isEqualTo(PREVIOUS_RUN);
    }

    @Test
    @DisplayName("AC-3: consecutive stale runs keep the first stale instant")
    void staleSinceIsKept() {
        seedPrevious(null, ALPHA, BETA, GAMMA);
        Instant firstStale = clock.instant();
        publisher.publish(day(List.of(), BETA));

        clock.advance(Duration.ofMinutes(15));
        publisher.publish(day(List.of(), BETA));

        assertThat(store.find(DAY).orElseThrow().staleSince()).isEqualTo(firstStale);
    }

    @Test
    @DisplayName("AC-4: a partial day without a previous snapshot is written as incomplete")
    void partialDayWithoutSnapshotIsWritten() {
        DayPublication publication = publisher.publish(day(List.of(aggregated("IB1", DAY, "08:00")), GAMMA));

        assertThat(publication).isEqualTo(DayPublication.publishedIncomplete(1, 0));
        assertThat(store.rows(DAY)).hasSize(1);
        assertThat(store.find(DAY).orElseThrow().providers()).containsExactly(ALPHA, BETA);
    }

    @Test
    @DisplayName("AC-5: after max-stale-age a partial day replaces the stale snapshot (incomplete)")
    void staleTooLongIsReplaced() {
        seedPrevious(clock.instant().minus(Duration.ofHours(2)), ALPHA, BETA, GAMMA);

        DayPublication publication = publisher.publish(day(List.of(aggregated("IB1", DAY, "08:00")), BETA));

        assertThat(publication.outcome()).isEqualTo(DayOutcome.PUBLISHED_INCOMPLETE);
        assertThat(store.find(DAY).orElseThrow().staleSince()).isNull();
    }

    @Test
    @DisplayName("AC-6: a day without data keeps an existing snapshot stale, or stays empty")
    void unavailableDay() {
        assertThat(publisher.unavailable(RUN, DAY)).isEqualTo(DayPublication.noData());
        assertThat(store.find(DAY)).isEmpty();

        seedPrevious(null, ALPHA, BETA, GAMMA);
        assertThat(publisher.unavailable(RUN, DAY)).isEqualTo(DayPublication.keptStale());
        assertThat(store.find(DAY).orElseThrow().staleSince()).isEqualTo(clock.instant());
        assertThat(store.rows(DAY)).hasSize(1);
    }

    @Test
    @DisplayName("AC-8 (error): a failed write is reported as WRITE_FAILED and the previous snapshot is untouched")
    void writeFailure() {
        seedPrevious(null, ALPHA, BETA, GAMMA);
        store.failWritesOf(DAY);

        DayPublication publication = publisher.publish(day(List.of(aggregated("IB1", DAY, "08:00"))));

        assertThat(publication).isEqualTo(DayPublication.writeFailed());
        assertThat(store.find(DAY).orElseThrow().runId()).isEqualTo(PREVIOUS_RUN);
        assertThat(store.rows(DAY)).hasSize(1);
    }

    @Test
    @DisplayName("AC-9: an itinerary whose first departure is on another UTC day is not stored and is counted")
    void misfiledItinerariesAreSkipped() {
        AggregatedItinerary onTheDay = aggregated("IB1", DAY, "08:00");
        AggregatedItinerary nextDay = aggregated("IB2", DAY.plusDays(1), "08:00");
        AggregatedItinerary previousDay = aggregated("IB3", DAY.minusDays(1), "08:00");

        DayPublication publication = publisher.publish(day(List.of(onTheDay, nextDay, previousDay)));

        assertThat(publication).isEqualTo(DayPublication.replaced(1, 2));
        assertThat(store.rows(DAY)).containsExactly(onTheDay);
    }
}
