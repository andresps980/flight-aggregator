package com.andresps.flights.ingestion.application.service;

import com.andresps.flights.aggregation.domain.deduplication.AggregatedItinerary;
import com.andresps.flights.ingestion.application.port.out.DayAggregationSink;
import com.andresps.flights.ingestion.application.port.out.DaySnapshotStore;
import com.andresps.flights.ingestion.application.port.out.DaySnapshotWrite;
import com.andresps.flights.ingestion.domain.DayAggregation;
import com.andresps.flights.ingestion.domain.DayPublication;
import com.andresps.flights.ingestion.domain.DaySnapshot;
import com.andresps.flights.ingestion.domain.SnapshotDecision;
import com.andresps.flights.ingestion.domain.SnapshotPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sink of the ingestion (spec 004): applies the {@link SnapshotPolicy} to each finished day and has the store
 * replace the day's snapshot or keep it stale (ADR-0017). Itineraries whose first departure is on another UTC day are
 * skipped and counted (AC-9). Store failures are reported as {@code WRITE_FAILED}, never thrown (AC-8).
 *
 * <p>Reading the current snapshot and then writing is safe without a transaction around both: only the run holding
 * the lock writes (ADR-0019), and each day is published once per run.
 */
public final class DaySnapshotPublisher implements DayAggregationSink {

    private static final Logger LOG = LoggerFactory.getLogger(DaySnapshotPublisher.class);

    private final DaySnapshotStore store;
    private final SnapshotPolicy policy;
    private final Clock clock;

    public DaySnapshotPublisher(DaySnapshotStore store, SnapshotPolicy policy, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public DayPublication publish(DayAggregation day) {
        try {
            List<AggregatedItinerary> onTheDay =
                    new ArrayList<>(day.result().itineraries().size());
            int misfiled = 0;
            for (AggregatedItinerary itinerary : day.result().itineraries()) {
                if (LocalDate.ofInstant(itinerary.itinerary().firstDeparture(), ZoneOffset.UTC)
                        .equals(day.day())) {
                    onTheDay.add(itinerary);
                } else {
                    misfiled++;
                }
            }
            if (misfiled > 0) {
                LOG.warn("{}: {} itineraries depart on another UTC day and were not stored", day.day(), misfiled);
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Optional<DaySnapshot> existing = store.find(day.day());
            if (policy.decide(existing, day.contributing(), day.missing(), now) == SnapshotDecision.KEEP_STALE) {
                store.markStale(day.day(), day.runId(), now);
                LOG.warn(
                        "{}: missing {}, kept the snapshot of run {} (providers {}) as stale",
                        day.day(),
                        day.missing(),
                        existing.map(DaySnapshot::runId).orElse(null),
                        existing.map(DaySnapshot::providers).orElse(null));
                return DayPublication.keptStale();
            }
            store.replace(
                    new DaySnapshotWrite(day.day(), day.runId(), onTheDay, day.contributing(), day.missing(), now));
            DayPublication publication = day.missing().isEmpty()
                    ? DayPublication.replaced(onTheDay.size(), misfiled)
                    : DayPublication.publishedIncomplete(onTheDay.size(), misfiled);
            LOG.info("{}: {} with {} itineraries", day.day(), publication.outcome(), onTheDay.size());
            return publication;
        } catch (RuntimeException e) {
            LOG.error("{}: storing the day failed; its previous snapshot is unchanged", day.day(), e);
            return DayPublication.writeFailed();
        }
    }

    @Override
    public DayPublication unavailable(UUID runId, LocalDate day) {
        try {
            if (store.find(day).isEmpty()) {
                LOG.info("{}: every provider failed and nothing is stored: NO_DATA", day);
                return DayPublication.noData();
            }
            store.markStale(day, runId, clock.instant().truncatedTo(ChronoUnit.MICROS));
            LOG.warn("{}: every provider failed, kept the previous snapshot as stale", day);
            return DayPublication.keptStale();
        } catch (RuntimeException e) {
            LOG.error("{}: marking the day stale failed", day, e);
            return DayPublication.writeFailed();
        }
    }
}
