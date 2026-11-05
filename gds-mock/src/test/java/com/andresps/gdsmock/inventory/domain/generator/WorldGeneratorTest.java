package com.andresps.gdsmock.inventory.domain.generator;

import static com.andresps.gdsmock.support.WorldFixtures.AGGREGATION_NOW;
import static com.andresps.gdsmock.support.WorldFixtures.ALPHA;
import static com.andresps.gdsmock.support.WorldFixtures.CLOCK;
import static com.andresps.gdsmock.support.WorldFixtures.PROVIDERS;
import static com.andresps.gdsmock.support.WorldFixtures.REFERENCE_DATE;
import static com.andresps.gdsmock.support.WorldFixtures.generator;
import static com.andresps.gdsmock.support.WorldFixtures.settings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.andresps.gdsmock.inventory.domain.model.Horizon;
import com.andresps.gdsmock.inventory.domain.model.InvalidKind;
import com.andresps.gdsmock.inventory.domain.model.MalformationKind;
import com.andresps.gdsmock.inventory.domain.model.ProviderRecord;
import com.andresps.gdsmock.inventory.domain.model.ScheduledSegment;
import com.andresps.gdsmock.inventory.domain.model.WorldItinerary;
import com.andresps.gdsmock.shared.domain.ProviderId;
import com.andresps.gdsmock.shared.domain.UnknownProviderException;
import com.andresps.gdsmock.support.ProviderPayloads;
import com.andresps.gdsmock.support.Spec001Rules;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WorldGeneratorTest {

    private static final int LARGE = 100_000;

    /** One 100k world with default fractions, shared by the statistical checks (generation is deterministic). */
    private static List<WorldItinerary> world;

    @BeforeAll
    static void generateLargeWorld() {
        world = generator(settings(LARGE)).itineraries().toList();
    }

    private static List<ProviderRecord> allRecords() {
        return world.stream().flatMap(i -> i.records().stream()).toList();
    }

    private static double ratio(long part, long total) {
        return (double) part / total;
    }

    @Nested
    class Determinism {

        @Test
        @DisplayName("AC-1: same seed, reference date and settings produce the same world")
        void sameSeedSameWorld() {
            var first = generator(settings(5_000)).itineraries().toList();
            var second = generator(settings(5_000)).itineraries().toList();

            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("AC-1: a different seed produces different data")
        void differentSeedDifferentWorld() {
            var seven = generator(settings(7L, 1_000, 0.05, 0.01, 0.005))
                    .itineraries()
                    .toList();
            var eight = generator(settings(8L, 1_000, 0.05, 0.01, 0.005))
                    .itineraries()
                    .toList();

            assertThat(eight).isNotEqualTo(seven);
        }

        @Test
        @DisplayName("AC-1: an itinerary does not depend on the volume (index-seeded generation)")
        void itineraryIndependentOfVolume() {
            var small = generator(settings(700))
                    .itineraries(LocalDate.of(2026, 11, 6))
                    .toList();
            var large = generator(settings(7_000))
                    .itineraries(LocalDate.of(2026, 11, 6))
                    .limit(small.size())
                    .toList();

            assertThat(large).isEqualTo(small);
        }
    }

    @Nested
    class HorizonAndPartitions {

        @Test
        @DisplayName("AC-2: the horizon is the 7 UTC days after the reference date")
        void horizonStartsTomorrow() {
            Horizon horizon = generator(settings(10)).horizon();

            assertThat(horizon.referenceDate()).isEqualTo(REFERENCE_DATE);
            assertThat(horizon.from()).isEqualTo(LocalDate.of(2026, 11, 6));
            assertThat(horizon.to()).isEqualTo(LocalDate.of(2026, 11, 12));
            assertThat(horizon.days()).hasSize(7);
            assertThat(horizon.contains(REFERENCE_DATE)).isFalse();
            assertThat(horizon.contains(LocalDate.of(2026, 11, 13))).isFalse();
        }

        @Test
        @DisplayName("AC-2: without a configured reference date, it is today in UTC from the clock")
        void referenceDateFromClock() {
            var settings = new WorldSettings(7, 10, 7, null, 0.05, 0.01, 0.005);
            var lateEvening = Clock.fixed(Instant.parse("2026-11-09T23:59:00Z"), ZoneOffset.UTC);

            Horizon horizon = new WorldGenerator(settings, PROVIDERS, lateEvening).horizon();

            assertThat(horizon.referenceDate()).isEqualTo(LocalDate.of(2026, 11, 9));
            assertThat(horizon.from()).isEqualTo(LocalDate.of(2026, 11, 10));
        }

        @Test
        @DisplayName("AC-2: every itinerary departs within its partition day, except ALREADY_DEPARTED ones")
        void departuresWithinPartitionDay() {
            Horizon horizon = generator(settings(LARGE)).horizon();

            assertThat(world).allSatisfy(itinerary -> {
                assertThat(horizon.contains(itinerary.partitionDay())).isTrue();
                LocalDate departureDay =
                        LocalDate.ofInstant(itinerary.segments().getFirst().departure(), ZoneOffset.UTC);
                if (itinerary.invalidKind().equals(java.util.Optional.of(InvalidKind.ALREADY_DEPARTED))) {
                    assertThat(departureDay).isEqualTo(REFERENCE_DATE.minusDays(1));
                } else {
                    assertThat(departureDay).isEqualTo(itinerary.partitionDay());
                }
            });
        }

        @Test
        @DisplayName("AC-2: the volume is split evenly over the horizon days")
        void volumeSplitEvenly() {
            Map<LocalDate, Long> perDay = generator(settings(7_003))
                    .itineraries()
                    .collect(Collectors.groupingBy(WorldItinerary::partitionDay, Collectors.counting()));

            assertThat(perDay).hasSize(7);
            assertThat(perDay.values()).allSatisfy(count -> assertThat(count).isBetween(1_000L, 1_001L));
            assertThat(perDay.values().stream().mapToLong(Long::longValue).sum())
                    .isEqualTo(7_003);
        }

        @Test
        @DisplayName("AC-13: day partitions are disjoint and, concatenated in order, equal the whole world")
        void partitionsUnionIsWholeWorld() {
            WorldGenerator generator = generator(settings(3_000));

            var concatenated = generator.horizon().days().stream()
                    .flatMap(generator::itineraries)
                    .toList();

            assertThat(concatenated).isEqualTo(generator.itineraries().toList());
            assertThat(generator.itineraries(LocalDate.of(2026, 11, 8)))
                    .allSatisfy(i -> assertThat(i.partitionDay()).isEqualTo(LocalDate.of(2026, 11, 8)));
        }

        @Test
        @DisplayName("AC-13: ALREADY_DEPARTED itineraries are served under their partition day")
        void departedItinerariesBelongToTheirPartition() {
            WorldGenerator generator = generator(settings(7L, 7_000, 0.5, 0.0, 0.0));
            LocalDate day = LocalDate.of(2026, 11, 10);

            assertThat(generator.itineraries(day))
                    .filteredOn(i -> i.invalidKind().equals(java.util.Optional.of(InvalidKind.ALREADY_DEPARTED)))
                    .isNotEmpty()
                    .allSatisfy(i -> assertThat(i.partitionDay()).isEqualTo(day));
        }

        @Test
        @DisplayName("AC-14 (error): a day outside the horizon is rejected")
        void dayOutsideHorizon() {
            WorldGenerator generator = generator(settings(10));

            assertThatThrownBy(() -> generator.itineraries(REFERENCE_DATE))
                    .isInstanceOf(DateOutsideHorizonException.class)
                    .hasMessageContaining("2026-11-06")
                    .hasMessageContaining("2026-11-12");
        }
    }

    @Nested
    class VolumeAndIdentity {

        @Test
        @DisplayName("AC-3: a 100,000-itinerary world has exactly 100,000 distinct identities")
        void exactDistinctIdentities() {
            Set<String> identities = new HashSet<>();
            world.forEach(i -> identities.add(identity(i.segments())));

            assertThat(world).hasSize(LARGE);
            assertThat(identities).hasSize(LARGE);
        }

        @Test
        @DisplayName("AC-3: a single-itinerary world works")
        void singleItinerary() {
            assertThat(generator(settings(1)).itineraries()).hasSize(1);
        }

        @Test
        @DisplayName("AC-3, AC-12: a 1,000,000-itinerary world is generated lazily, one itinerary at a time")
        void millionItinerariesLazily() {
            var settings = new WorldSettings(7, 1_000_000, 7, REFERENCE_DATE, 0.05, 0.01, 0.005);

            long alphaRecords = generator(settings).records(ALPHA).count();

            assertThat(alphaRecords).isBetween(550_000L, 750_000L);
        }

        private static String identity(List<ScheduledSegment> segments) {
            return segments.stream()
                    .map(s -> s.airline() + s.flightNumber() + ":" + s.origin().code() + "-"
                            + s.destination().code() + "@" + s.departure())
                    .collect(Collectors.joining("|"));
        }
    }

    @Nested
    class Providers {

        @Test
        @DisplayName("AC-4: each itinerary is offered by 1, 2 or 3 providers, about 40/35/25 %")
        void providerCountDistribution() {
            Map<Integer, Long> byCount = world.stream()
                    .collect(Collectors.groupingBy(i -> i.providers().size(), Collectors.counting()));

            assertThat(byCount.keySet()).containsExactlyInAnyOrder(1, 2, 3);
            assertThat(ratio(byCount.get(1), LARGE)).isCloseTo(0.40, within(0.02));
            assertThat(ratio(byCount.get(2), LARGE)).isCloseTo(0.35, within(0.02));
            assertThat(ratio(byCount.get(3), LARGE)).isCloseTo(0.25, within(0.02));
        }

        @Test
        @DisplayName("AC-4: providers of the same itinerary share its schedule and price it independently")
        void sameScheduleDifferentPrices() {
            var multiProvider =
                    world.stream().filter(i -> i.providers().size() > 1).toList();
            long withDifferentPrices = multiProvider.stream()
                    .filter(i -> i.records().stream()
                                    .map(r -> r.price().stripTrailingZeros())
                                    .distinct()
                                    .count()
                            > 1)
                    .count();

            assertThat(multiProvider)
                    .allSatisfy(i -> assertThat(i.records())
                            .filteredOn(r -> r.malformation().isEmpty())
                            .allSatisfy(r -> assertThat(r.segments()).isEqualTo(i.segments())));
            assertThat(ratio(withDifferentPrices, multiProvider.size())).isGreaterThan(0.95);
        }

        @Test
        @DisplayName("AC-4: about 0.5 % of records get one same-provider duplicate with another price and id")
        void sameProviderDuplicates() {
            List<ProviderRecord> records = allRecords();
            long originals = records.stream().filter(r -> r.copy() == 0).count();
            long duplicates = records.stream().filter(r -> r.copy() == 1).count();

            assertThat(ratio(duplicates, originals)).isCloseTo(0.005, within(0.001));
            assertThat(records).allSatisfy(r -> assertThat(r.copy()).isBetween(0, 1));
            for (WorldItinerary itinerary : world) {
                Map<ProviderId, List<ProviderRecord>> byProvider =
                        itinerary.records().stream().collect(Collectors.groupingBy(ProviderRecord::provider));
                byProvider.values().forEach(own -> {
                    assertThat(own).hasSizeBetween(1, 2);
                    if (own.size() == 2) {
                        assertThat(own.get(0).copy()).isZero();
                        assertThat(own.get(1).copy()).isEqualTo(1);
                        assertThat(own.get(1).price())
                                .isNotEqualByComparingTo(own.get(0).price());
                        assertThat(own.get(1).recordId())
                                .isNotEqualTo(own.get(0).recordId());
                    }
                });
            }
        }

        @Test
        @DisplayName("AC-4: no same-provider duplicates when the fraction is 0")
        void noDuplicatesWhenDisabled() {
            assertThat(generator(settings(7L, 5_000, 0.05, 0.01, 0.0)).itineraries())
                    .flatMap(WorldItinerary::records)
                    .allSatisfy(r -> assertThat(r.copy()).isZero());
        }

        @Test
        @DisplayName("AC-11: a provider's records are its records of the world, in generation order")
        void providerRecordsInGenerationOrder() {
            WorldGenerator generator = generator(settings(2_000));

            var expected = generator
                    .itineraries()
                    .flatMap(i -> i.records().stream())
                    .filter(r -> r.provider().equals(ALPHA))
                    .toList();

            assertThat(generator.records(ALPHA).toList()).isEqualTo(expected);
            assertThat(generator.records(ALPHA, LocalDate.of(2026, 11, 7)))
                    .isNotEmpty()
                    .allSatisfy(r -> assertThat(r.recordId()).startsWith("20261107-"));
        }

        @Test
        @DisplayName("AC-14 (error): records of an unknown provider are rejected")
        void unknownProvider() {
            WorldGenerator generator = generator(settings(10));

            assertThatThrownBy(() -> generator.records(new ProviderId("mock-delta")))
                    .isInstanceOf(UnknownProviderException.class)
                    .hasMessageContaining("mock-delta");
        }
    }

    @Nested
    class ValidItineraries {

        @Test
        @DisplayName("AC-5: valid itineraries pass every spec 001 rule")
        void validItinerariesPassSpec001() {
            assertThat(world)
                    .filteredOn(i -> i.invalidKind().isEmpty())
                    .allSatisfy(i -> assertThat(Spec001Rules.firstFailure(
                                    ProviderPayloads.toTestSegments(i.segments()), AGGREGATION_NOW))
                            .isEmpty());
        }

        @Test
        @DisplayName("AC-5: valid itineraries have 1-3 segments and connections mostly between 1 and 6 hours")
        void segmentCountsAndConnections() {
            var valid = world.stream().filter(i -> i.invalidKind().isEmpty()).toList();
            var connections = valid.stream()
                    .flatMap(i -> Spec001Rules.connections(ProviderPayloads.toTestSegments(i.segments())).stream())
                    .toList();
            long typical = connections.stream()
                    .filter(c -> c.compareTo(Duration.ofHours(1)) >= 0 && c.compareTo(Duration.ofHours(6)) <= 0)
                    .count();

            assertThat(valid.stream().map(i -> i.segments().size()).distinct()).containsExactlyInAnyOrder(1, 2, 3);
            assertThat(ratio(typical, connections.size())).isGreaterThan(0.8);
        }
    }

    @Nested
    class InvalidItineraries {

        @Test
        @DisplayName("AC-6: about 5 % of itineraries are invalid, every kind present and spread about evenly")
        void invalidFractionAndSpread() {
            Map<InvalidKind, Long> byKind = new EnumMap<>(InvalidKind.class);
            world.forEach(i -> i.invalidKind().ifPresent(k -> byKind.merge(k, 1L, Long::sum)));
            long invalid = byKind.values().stream().mapToLong(Long::longValue).sum();

            assertThat(ratio(invalid, LARGE)).isCloseTo(0.05, within(0.005));
            assertThat(byKind).containsOnlyKeys(InvalidKind.values());
            byKind.values().forEach(count -> assertThat(ratio(count, LARGE)).isCloseTo(0.05 / 7, within(0.0025)));
        }

        @Test
        @DisplayName("AC-6: each invalid itinerary's first failing spec 001 rule is its kind")
        void firstFailingRuleIsTheKind() {
            assertThat(world)
                    .filteredOn(i -> i.invalidKind().isPresent())
                    .allSatisfy(i -> assertThat(Spec001Rules.firstFailure(
                                    ProviderPayloads.toTestSegments(i.segments()), AGGREGATION_NOW))
                            .contains(i.invalidKind().orElseThrow().name()));
        }

        @Test
        @DisplayName("AC-6: invalid itineraries are offered and duplicated like valid ones")
        void invalidItinerariesAreOffered() {
            var invalid =
                    world.stream().filter(i -> i.invalidKind().isPresent()).toList();

            assertThat(invalid).allSatisfy(i -> assertThat(i.records()).isNotEmpty());
            assertThat(invalid.stream().map(i -> i.providers().size()).distinct())
                    .containsExactlyInAnyOrder(1, 2, 3);
        }

        @Test
        @DisplayName("AC-6: with an invalid fraction of 0 there are no invalid itineraries")
        void noInvalidWhenDisabled() {
            assertThat(generator(settings(7L, 5_000, 0.0, 0.01, 0.005)).itineraries())
                    .allSatisfy(i -> assertThat(i.invalidKind()).isEmpty());
        }
    }

    @Nested
    class MalformedRecords {

        @Test
        @DisplayName("AC-7: about 1 % of records are malformed, with every kind present")
        void malformedFractionAndKinds() {
            List<ProviderRecord> records = allRecords();
            Map<MalformationKind, Long> byKind = records.stream()
                    .flatMap(r -> r.malformation().stream())
                    .collect(Collectors.groupingBy(
                            m -> m.kind(), () -> new EnumMap<>(MalformationKind.class), Collectors.counting()));
            long malformed = byKind.values().stream().mapToLong(Long::longValue).sum();

            assertThat(ratio(malformed, records.size())).isCloseTo(0.01, within(0.002));
            assertThat(byKind).containsOnlyKeys(MalformationKind.values());
        }

        @Test
        @DisplayName("AC-7: each malformation breaks only its own field")
        void malformationsBreakTheirField() {
            Map<String, Function<ProviderRecord, Boolean>> checks = Map.of(
                    "badCode",
                            r -> r.segments().stream()
                                    .anyMatch(s -> !s.origin().code().matches("[A-Z]{3}")
                                            || !s.destination().code().matches("[A-Z]{3}")),
                    "nonPositivePrice", r -> r.price().signum() <= 0);

            assertThat(allRecords()).allSatisfy(r -> {
                MalformationKind kind = r.malformation().map(m -> m.kind()).orElse(null);
                assertThat(checks.get("badCode").apply(r)).isEqualTo(kind == MalformationKind.BAD_AIRPORT_CODE);
                assertThat(checks.get("nonPositivePrice").apply(r))
                        .isEqualTo(kind == MalformationKind.NON_POSITIVE_PRICE);
                r.malformation()
                        .ifPresent(m -> assertThat(m.segmentIndex())
                                .isBetween(0, r.segments().size() - 1));
            });
        }

        @Test
        @DisplayName("AC-7: a malformed record does not affect other providers' records of the same itinerary")
        void otherProvidersUnaffected() {
            var withMalformed = world.stream()
                    .filter(i -> i.providers().size() > 1)
                    .filter(i ->
                            i.records().stream().anyMatch(r -> r.malformation().isPresent()))
                    .toList();

            assertThat(withMalformed).isNotEmpty();
            assertThat(withMalformed)
                    .allSatisfy(i -> assertThat(i.records())
                            .anyMatch(r -> r.malformation().isEmpty()));
        }

        @Test
        @DisplayName("AC-7: with a malformed fraction of 0 there are no malformed records")
        void noMalformedWhenDisabled() {
            assertThat(generator(settings(7L, 5_000, 0.05, 0.0, 0.005)).itineraries())
                    .flatMap(WorldItinerary::records)
                    .allSatisfy(r -> assertThat(r.malformation()).isEmpty());
        }
    }

    @Test
    @DisplayName("AC-1: generation never reads the clock when a reference date is configured")
    void configuredReferenceDateWins() {
        var otherDay = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC);
        var settings = settings(500);

        var fromClock =
                new WorldGenerator(settings, PROVIDERS, otherDay).itineraries().toList();

        assertThat(fromClock)
                .isEqualTo(new WorldGenerator(settings, PROVIDERS, CLOCK)
                        .itineraries()
                        .toList());
    }
}
