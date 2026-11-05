package com.andresps.flights.ingestion.infrastructure.adapter.out.memory;

import static com.andresps.flights.aggregation.domain.FlightFixtures.ALPHA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.BETA;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.aggregation.domain.deduplication.AggregationResult;
import com.andresps.flights.aggregation.domain.deduplication.AggregationStatistics;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.ingestion.domain.DayAggregation;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The in-memory result adapter: keeps the latest published snapshot of each day (spec 003, persistence notes). */
class InMemoryDayAggregationStoreTest {

    private static final LocalDate DAY1 = LocalDate.parse("2026-11-06");
    private static final LocalDate DAY2 = LocalDate.parse("2026-11-07");

    private final InMemoryDayAggregationStore store = new InMemoryDayAggregationStore();

    private static DayAggregation day(LocalDate day, ProviderId... missing) {
        TreeSet<ProviderId> missingSet = new TreeSet<>(List.of(missing));
        TreeSet<ProviderId> contributing = new TreeSet<>(List.of(ALPHA, BETA));
        contributing.removeAll(missingSet);
        return new DayAggregation(
                day, new AggregationResult(List.of(), AggregationStatistics.empty()), contributing, missingSet);
    }

    @Test
    @DisplayName("AC-11: published days are kept in day order and can be looked up")
    void keepsPublishedDays() {
        store.publish(day(DAY2));
        store.publish(day(DAY1));

        assertThat(store.days()).containsExactly(DAY1, DAY2);
        assertThat(store.day(DAY1)).hasValueSatisfying(d -> assertThat(d.day()).isEqualTo(DAY1));
        assertThat(store.day(LocalDate.parse("2026-11-08"))).isEmpty();
    }

    @Test
    @DisplayName("AC-11: a new publication of a day replaces its snapshot (merging is the persistence spec's job)")
    void replacesDaySnapshot() {
        store.publish(day(DAY1));
        store.publish(day(DAY1, BETA));

        assertThat(store.day(DAY1).orElseThrow().missing()).containsExactly(BETA);
        assertThat(store.days()).containsExactly(DAY1);
    }

    @Test
    @DisplayName("clear() empties the store")
    void clears() {
        store.publish(day(DAY1));

        store.clear();

        assertThat(store.days()).isEmpty();
    }
}
