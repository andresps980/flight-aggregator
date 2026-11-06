package com.andresps.flights.ingestion.application.service;

import com.andresps.flights.aggregation.domain.deduplication.AggregatedItinerary;
import com.andresps.flights.ingestion.application.port.out.DaySnapshotStore;
import com.andresps.flights.ingestion.application.port.out.DaySnapshotWrite;
import com.andresps.flights.ingestion.application.port.out.SnapshotMaintenance;
import com.andresps.flights.ingestion.domain.DaySnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory test double of the snapshot store (replaces spec 003's in-memory sink, now test-only): keeps each day's
 * rows and snapshot state, and can be told to fail a write.
 */
final class InMemoryDaySnapshotStore implements DaySnapshotStore, SnapshotMaintenance {

    private final Map<LocalDate, List<AggregatedItinerary>> rows = new ConcurrentSkipListMap<>();
    private final Map<LocalDate, DaySnapshot> snapshots = new ConcurrentSkipListMap<>();
    private final List<LocalDate> deletedBefore = new ArrayList<>();
    private final AtomicInteger statisticsRefreshes = new AtomicInteger();
    private volatile LocalDate failWritesOf;

    @Override
    public Optional<DaySnapshot> find(LocalDate day) {
        return Optional.ofNullable(snapshots.get(day));
    }

    @Override
    public void replace(DaySnapshotWrite write) {
        if (write.day().equals(failWritesOf)) {
            throw new IllegalStateException("database down (test)");
        }
        rows.put(write.day(), List.copyOf(write.itineraries()));
        snapshots.put(
                write.day(), new DaySnapshot(write.day(), write.runId(), write.providers(), write.publishedAt(), null));
    }

    @Override
    public void markStale(LocalDate day, UUID runId, Instant at) {
        DaySnapshot current = snapshots.get(day);
        Instant since = current.staleSince() == null ? at : current.staleSince();
        snapshots.put(day, new DaySnapshot(day, current.runId(), current.providers(), current.publishedAt(), since));
    }

    @Override
    public synchronized void deleteDaysBefore(LocalDate day) {
        deletedBefore.add(day);
        rows.keySet().removeIf(d -> d.isBefore(day));
        snapshots.keySet().removeIf(d -> d.isBefore(day));
    }

    @Override
    public void refreshStatistics() {
        statisticsRefreshes.incrementAndGet();
    }

    void failWritesOf(LocalDate day) {
        failWritesOf = day;
    }

    /** Seeds a day as if an earlier run had written it. */
    void seed(DaySnapshot snapshot, List<AggregatedItinerary> itineraries) {
        snapshots.put(snapshot.day(), snapshot);
        rows.put(snapshot.day(), List.copyOf(itineraries));
    }

    List<AggregatedItinerary> rows(LocalDate day) {
        return rows.getOrDefault(day, List.of());
    }

    synchronized List<LocalDate> deletedBefore() {
        return List.copyOf(deletedBefore);
    }

    int statisticsRefreshes() {
        return statisticsRefreshes.get();
    }
}
