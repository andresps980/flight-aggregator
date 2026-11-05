package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web;

import com.andresps.gdsmock.inventory.domain.model.Horizon;
import com.andresps.gdsmock.inventory.domain.stats.WorldStatistics;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Computes world statistics once per (reference date, partition day) and keeps them: the world only changes when the
 * reference date does (at midnight UTC without a configured date), and then older entries are dropped.
 *
 * <p>The computation (about 1.7 s at 1,000,000 itineraries) runs outside any map lock: the first caller inserts a
 * future and computes, concurrent callers wait on the future. A lock held that long would pin virtual threads' carrier
 * threads on JDK 21.
 */
public final class WorldStatisticsCache {

    private record Key(LocalDate referenceDate, Optional<LocalDate> day) {}

    private final BiFunction<Horizon, Optional<LocalDate>, WorldStatistics> compute;
    private final Map<Key, CompletableFuture<WorldStatistics>> cache = new ConcurrentHashMap<>();

    /** @param compute statistics of the given horizon, for one partition day or the whole horizon */
    public WorldStatisticsCache(BiFunction<Horizon, Optional<LocalDate>, WorldStatistics> compute) {
        this.compute = compute;
    }

    public WorldStatistics get(Horizon horizon, Optional<LocalDate> day) {
        cache.keySet().removeIf(key -> !key.referenceDate().equals(horizon.referenceDate()));
        Key key = new Key(horizon.referenceDate(), day);
        CompletableFuture<WorldStatistics> mine = new CompletableFuture<>();
        CompletableFuture<WorldStatistics> existing = cache.putIfAbsent(key, mine);
        if (existing != null) {
            return existing.join();
        }
        try {
            WorldStatistics statistics = compute.apply(horizon, day);
            mine.complete(statistics);
            return statistics;
        } catch (RuntimeException e) {
            cache.remove(key, mine);
            mine.completeExceptionally(e);
            throw e;
        }
    }
}
