package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web;

import com.andresps.gdsmock.inventory.domain.generator.WorldGenerator;
import com.andresps.gdsmock.inventory.domain.stats.WorldStatistics;
import com.andresps.gdsmock.inventory.domain.stats.WorldStatisticsCalculator;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Computes world statistics once per (reference date, partition day) and keeps them: the world only changes when the
 * reference date does (at midnight UTC without a configured date), and then older entries are dropped.
 */
public final class WorldStatisticsCache {

    private record Key(LocalDate referenceDate, Optional<LocalDate> day) {}

    private final WorldGenerator generator;
    private final WorldStatisticsCalculator calculator;
    private final Map<Key, WorldStatistics> cache = new ConcurrentHashMap<>();

    public WorldStatisticsCache(WorldGenerator generator, WorldStatisticsCalculator calculator) {
        this.generator = generator;
        this.calculator = calculator;
    }

    public WorldStatistics get(Optional<LocalDate> day) {
        LocalDate referenceDate = generator.horizon().referenceDate();
        cache.keySet().removeIf(key -> !key.referenceDate().equals(referenceDate));
        return cache.computeIfAbsent(
                new Key(referenceDate, day),
                key -> calculator.calculate(day.map(generator::itineraries).orElseGet(generator::itineraries)));
    }
}
