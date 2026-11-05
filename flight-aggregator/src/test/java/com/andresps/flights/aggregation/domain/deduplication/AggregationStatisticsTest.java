package com.andresps.flights.aggregation.domain.deduplication;

import static com.andresps.flights.aggregation.domain.FlightFixtures.ALPHA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.BETA;
import static com.andresps.flights.aggregation.domain.FlightFixtures.GAMMA;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.aggregation.domain.validation.DiscardReason;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec 003 AC-10: statistics of disjoint (per-day) aggregations add up counter by counter. */
class AggregationStatisticsTest {

    @Test
    @DisplayName("AC-10: plus() sums every counter, including keys present on one side only")
    void plusSumsCounters() {
        AggregationStatistics day1 =
                new AggregationStatistics(Map.of(ALPHA, 3L, BETA, 2L), Map.of(DiscardReason.MIXED_AIRLINES, 1L), 1, 3);
        AggregationStatistics day2 = new AggregationStatistics(
                Map.of(BETA, 1L, GAMMA, 4L),
                Map.of(DiscardReason.MIXED_AIRLINES, 2L, DiscardReason.ALREADY_DEPARTED, 1L),
                0,
                2);

        AggregationStatistics sum = day1.plus(day2);

        assertThat(sum.offersReceivedByProvider())
                .containsExactlyInAnyOrderEntriesOf(Map.of(ALPHA, 3L, BETA, 3L, GAMMA, 4L));
        assertThat(sum.discardedByReason())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(DiscardReason.MIXED_AIRLINES, 3L, DiscardReason.ALREADY_DEPARTED, 1L));
        assertThat(sum.duplicatesMerged()).isEqualTo(1);
        assertThat(sum.itinerariesProduced()).isEqualTo(5);
        assertThat(sum.totalReceived())
                .isEqualTo(sum.totalDiscarded() + sum.duplicatesMerged() + sum.itinerariesProduced());
    }

    @Test
    @DisplayName("AC-10: empty() is the identity of plus()")
    void emptyIsIdentity() {
        AggregationStatistics stats =
                new AggregationStatistics(Map.of(ALPHA, 2L), Map.of(DiscardReason.LAYOVER_TOO_LONG, 1L), 0, 1);

        assertThat(AggregationStatistics.empty().plus(stats)).isEqualTo(stats);
        assertThat(stats.plus(AggregationStatistics.empty())).isEqualTo(stats);
        assertThat(AggregationStatistics.empty().totalReceived()).isZero();
    }
}
