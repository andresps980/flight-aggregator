package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.gdsmock.inventory.domain.model.Horizon;
import com.andresps.gdsmock.inventory.domain.stats.ExpectedAggregation;
import com.andresps.gdsmock.inventory.domain.stats.FullyMalformedItineraries;
import com.andresps.gdsmock.inventory.domain.stats.WorldStatistics;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorldStatisticsCacheTest {

    private static final Horizon NOV_5 = Horizon.after(LocalDate.of(2026, 11, 5), 7);
    private static final Horizon NOV_6 = Horizon.after(LocalDate.of(2026, 11, 6), 7);

    private final AtomicInteger computations = new AtomicInteger();

    private static WorldStatistics stats(long distinct) {
        return new WorldStatistics(
                distinct,
                Map.of(),
                0,
                Map.of(),
                new FullyMalformedItineraries(0, Map.of()),
                new ExpectedAggregation(Map.of(), Map.of(), 0, 0));
    }

    private final WorldStatisticsCache cache = new WorldStatisticsCache((horizon, day) -> {
        computations.incrementAndGet();
        return stats(horizon.referenceDate().getDayOfMonth() * 100L
                + day.map(LocalDate::getDayOfMonth).orElse(0));
    });

    @Test
    @DisplayName("AC-20: figures are computed once per reference date and day, then served from the cache")
    void computesOncePerKey() {
        var whole = cache.get(NOV_5, Optional.empty());
        var again = cache.get(NOV_5, Optional.empty());
        var day = cache.get(NOV_5, Optional.of(LocalDate.of(2026, 11, 8)));

        assertThat(again).isSameAs(whole);
        assertThat(day.distinctItineraries()).isEqualTo(508);
        assertThat(computations).hasValue(2);
    }

    @Test
    @DisplayName("AC-20: a new reference date recomputes and drops the previous day's entries")
    void referenceDateChangeEvicts() {
        cache.get(NOV_5, Optional.empty());

        var next = cache.get(NOV_6, Optional.empty());
        var backAgain = cache.get(NOV_5, Optional.empty());

        assertThat(next.distinctItineraries()).isEqualTo(600);
        assertThat(backAgain.distinctItineraries()).isEqualTo(500);
        assertThat(computations).hasValue(3);
    }

    @Test
    @DisplayName("AC-20: concurrent first calls compute once; the others wait for the same result")
    void concurrentCallersShareOneComputation() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var slow = new WorldStatisticsCache((horizon, day) -> {
            computations.incrementAndGet();
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return stats(42);
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<WorldStatistics> first = executor.submit(() -> slow.get(NOV_5, Optional.empty()));
            started.await();
            List<Future<WorldStatistics>> others = List.of(
                    executor.submit(() -> slow.get(NOV_5, Optional.empty())),
                    executor.submit(() -> slow.get(NOV_5, Optional.empty())));
            release.countDown();

            assertThat(first.get().distinctItineraries()).isEqualTo(42);
            for (Future<WorldStatistics> other : others) {
                assertThat(other.get()).isSameAs(first.get());
            }
        }
        assertThat(computations).hasValue(1);
    }

    @Test
    @DisplayName("AC-20: a failed computation is not cached")
    void failureNotCached() {
        var flaky = new WorldStatisticsCache((horizon, day) -> {
            if (computations.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
            return stats(1);
        });

        assertThatThrownBy(() -> flaky.get(NOV_5, Optional.empty())).hasMessage("boom");
        assertThat(flaky.get(NOV_5, Optional.empty()).distinctItineraries()).isEqualTo(1);
    }
}
