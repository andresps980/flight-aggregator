package com.andresps.gdsmock.inventory.domain.stats;

import static com.andresps.gdsmock.support.WorldFixtures.ALPHA;
import static com.andresps.gdsmock.support.WorldFixtures.BETA;
import static com.andresps.gdsmock.support.WorldFixtures.GAMMA;
import static com.andresps.gdsmock.support.WorldFixtures.PROVIDERS;
import static com.andresps.gdsmock.support.WorldFixtures.generator;
import static com.andresps.gdsmock.support.WorldFixtures.settings;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.gdsmock.inventory.domain.model.Airport;
import com.andresps.gdsmock.inventory.domain.model.InvalidKind;
import com.andresps.gdsmock.inventory.domain.model.Malformation;
import com.andresps.gdsmock.inventory.domain.model.MalformationKind;
import com.andresps.gdsmock.inventory.domain.model.ProviderRecord;
import com.andresps.gdsmock.inventory.domain.model.ScheduledSegment;
import com.andresps.gdsmock.inventory.domain.model.WorldItinerary;
import com.andresps.gdsmock.shared.domain.ProviderId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorldStatisticsCalculatorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    private static final Airport MAD = new Airport("MAD", ZoneId.of("Europe/Madrid"));
    private static final Airport BCN = new Airport("BCN", ZoneId.of("Europe/Madrid"));
    private static final Airport M4D = new Airport("M4D", ZoneId.of("Europe/Madrid"));

    private final WorldStatisticsCalculator calculator = new WorldStatisticsCalculator(PROVIDERS);

    @Test
    @DisplayName("AC-20: figures for a hand-built world with every kind of duplicate, invalid and malformed record")
    void handBuiltWorld() {
        List<WorldItinerary> world = List.of(
                // A: valid, offered by all three providers
                itinerary(0, null, record(ALPHA, 0, 0, null), record(BETA, 0, 0, null), record(GAMMA, 0, 0, null)),
                // B: valid, Mock-Alpha twice (same-provider duplicate)
                itinerary(1, null, record(ALPHA, 1, 0, null), record(ALPHA, 1, 1, null)),
                // C: valid, malformed at Mock-Beta, fine at Mock-Gamma
                itinerary(2, null, record(BETA, 2, 0, MalformationKind.NON_POSITIVE_PRICE), record(GAMMA, 2, 0, null)),
                // D: valid, its only record is malformed
                itinerary(3, null, record(GAMMA, 3, 0, MalformationKind.BAD_AIRPORT_CODE)),
                // E: invalid, offered by two providers
                itinerary(4, InvalidKind.MIXED_AIRLINES, record(ALPHA, 4, 0, null), record(BETA, 4, 0, null)),
                // F: invalid, its only record is malformed
                itinerary(5, InvalidKind.ALREADY_DEPARTED, record(GAMMA, 5, 0, MalformationKind.UNPARSEABLE_TIME)));

        WorldStatistics stats = calculator.calculate(world.stream());

        assertThat(stats.distinctItineraries()).isEqualTo(6);
        assertThat(stats.invalidItineraries())
                .containsOnlyKeys(InvalidKind.values())
                .containsEntry(InvalidKind.MIXED_AIRLINES, 1L)
                .containsEntry(InvalidKind.ALREADY_DEPARTED, 1L)
                .containsEntry(InvalidKind.LAYOVER_TOO_LONG, 0L);
        assertThat(stats.crossProviderDuplicates()).isEqualTo(4);

        assertThat(stats.providers()).containsOnlyKeys(ALPHA, BETA, GAMMA);
        assertThat(stats.providers().get(ALPHA).records()).isEqualTo(4);
        assertThat(stats.providers().get(ALPHA).sameProviderDuplicates()).isEqualTo(1);
        assertThat(stats.providers().get(ALPHA).malformed()).containsOnly(zeros());
        assertThat(stats.providers().get(BETA).records()).isEqualTo(3);
        assertThat(stats.providers().get(BETA).malformed()).containsEntry(MalformationKind.NON_POSITIVE_PRICE, 1L);
        assertThat(stats.providers().get(GAMMA).records()).isEqualTo(4);
        assertThat(stats.providers().get(GAMMA).malformed())
                .containsEntry(MalformationKind.BAD_AIRPORT_CODE, 1L)
                .containsEntry(MalformationKind.UNPARSEABLE_TIME, 1L)
                .containsEntry(MalformationKind.NON_POSITIVE_PRICE, 0L);

        assertThat(stats.fullyMalformedItineraries().total()).isEqualTo(2);
        assertThat(stats.fullyMalformedItineraries().byInvalidKind())
                .containsOnlyKeys(InvalidKind.values())
                .containsEntry(InvalidKind.ALREADY_DEPARTED, 1L)
                .containsEntry(InvalidKind.MIXED_AIRLINES, 0L);
        assertThat(stats.providers().get(ALPHA).fullyMalformedItineraries()).isZero();
        assertThat(stats.providers().get(BETA).fullyMalformedItineraries()).isEqualTo(1);
        assertThat(stats.providers().get(GAMMA).fullyMalformedItineraries()).isEqualTo(2);

        ExpectedAggregation expected = stats.expectedAggregation();
        assertThat(expected.offersReceived())
                .containsExactlyInAnyOrderEntriesOf(Map.of(ALPHA, 4L, BETA, 2L, GAMMA, 2L));
        assertThat(expected.discarded())
                .containsOnlyKeys(InvalidKind.values())
                .containsEntry(InvalidKind.MIXED_AIRLINES, 2L)
                .containsEntry(InvalidKind.ALREADY_DEPARTED, 0L);
        assertThat(expected.duplicatesMerged()).isEqualTo(3);
        assertThat(expected.itinerariesProduced()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-20: an empty world has all counters at zero, with every provider and kind listed")
    void emptyWorld() {
        WorldStatistics stats = calculator.calculate(Stream.empty());

        assertThat(stats.fullyMalformedItineraries().total()).isZero();

        assertThat(stats.distinctItineraries()).isZero();
        assertThat(stats.crossProviderDuplicates()).isZero();
        assertThat(stats.providers()).containsOnlyKeys(ALPHA, BETA, GAMMA);
        assertThat(stats.expectedAggregation().offersReceived())
                .containsOnly(Map.entry(ALPHA, 0L), Map.entry(BETA, 0L), Map.entry(GAMMA, 0L));
        assertThat(stats.expectedAggregation().itinerariesProduced()).isZero();
    }

    @Test
    @DisplayName("AC-20: received = discarded + merged + produced on a generated world")
    void reconciles() {
        WorldStatistics stats = calculator.calculate(
                generator(settings(7L, 20_000, 0.2, 0.05, 0.05)).itineraries());
        ExpectedAggregation expected = stats.expectedAggregation();

        long received = sum(expected.offersReceived());
        long discarded = sum(expected.discarded());

        assertThat(received).isEqualTo(discarded + expected.duplicatesMerged() + expected.itinerariesProduced());
        long records = stats.providers().values().stream()
                .mapToLong(ProviderStatistics::records)
                .sum();
        long malformed = stats.providers().values().stream()
                .mapToLong(p -> sum(p.malformed()))
                .sum();
        assertThat(received).isEqualTo(records - malformed);
        assertThat(records)
                .isEqualTo(stats.distinctItineraries()
                        + stats.crossProviderDuplicates()
                        + stats.providers().values().stream()
                                .mapToLong(ProviderStatistics::sameProviderDuplicates)
                                .sum());
    }

    private static long sum(Map<?, Long> counters) {
        return counters.values().stream().mapToLong(Long::longValue).sum();
    }

    private static Map.Entry<MalformationKind, Long>[] zeros() {
        List<Map.Entry<MalformationKind, Long>> entries = new ArrayList<>();
        for (MalformationKind kind : MalformationKind.values()) {
            entries.add(Map.entry(kind, 0L));
        }
        @SuppressWarnings("unchecked")
        Map.Entry<MalformationKind, Long>[] array = entries.toArray(new Map.Entry[0]);
        return array;
    }

    private static List<ScheduledSegment> segments(int index) {
        Instant departure = Instant.parse("2026-11-10T08:00:00Z").plusSeconds(60L * index);
        return List.of(new ScheduledSegment("IB", 100 + index, MAD, BCN, departure, departure.plusSeconds(4_500)));
    }

    private static WorldItinerary itinerary(int index, InvalidKind kind, ProviderRecord... records) {
        return new WorldItinerary(
                DAY, index, segments(index), new BigDecimal("100.00"), Optional.ofNullable(kind), List.of(records));
    }

    private static ProviderRecord record(ProviderId provider, int index, int copy, MalformationKind malformation) {
        List<ScheduledSegment> segments = segments(index);
        BigDecimal price = new BigDecimal("100.00").add(BigDecimal.valueOf(copy));
        if (malformation == MalformationKind.BAD_AIRPORT_CODE) {
            ScheduledSegment s = segments.getFirst();
            segments = List.of(new ScheduledSegment(
                    s.airline(), s.flightNumber(), M4D, s.destination(), s.departure(), s.arrival()));
        }
        if (malformation == MalformationKind.NON_POSITIVE_PRICE) {
            price = BigDecimal.ZERO.setScale(2);
        }
        return new ProviderRecord(
                provider,
                "20261110-%06d-%d".formatted(index, copy),
                copy,
                segments,
                price,
                Optional.ofNullable(malformation).map(kind -> new Malformation(kind, 0)));
    }
}
