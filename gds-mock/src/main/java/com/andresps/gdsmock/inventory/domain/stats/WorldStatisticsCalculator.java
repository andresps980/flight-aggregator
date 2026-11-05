package com.andresps.gdsmock.inventory.domain.stats;

import com.andresps.gdsmock.inventory.domain.model.InvalidKind;
import com.andresps.gdsmock.inventory.domain.model.MalformationKind;
import com.andresps.gdsmock.inventory.domain.model.ProviderRecord;
import com.andresps.gdsmock.inventory.domain.model.WorldItinerary;
import com.andresps.gdsmock.shared.domain.ProviderId;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Computes {@link WorldStatistics} in one pass with counters: memory doesn't depend on the number of itineraries. */
public final class WorldStatisticsCalculator {

    private final List<ProviderId> providers;

    public WorldStatisticsCalculator(List<ProviderId> providers) {
        this.providers = List.copyOf(providers);
    }

    public WorldStatistics calculate(Stream<WorldItinerary> itineraries) {
        Counters counters = new Counters();
        itineraries.forEach(counters::add);
        return counters.result();
    }

    private final class Counters {
        private long distinct;
        private long crossProviderDuplicates;
        private long fullyMalformed;
        private long duplicatesMerged;
        private long produced;
        private final Map<InvalidKind, Long> invalid = zeros(InvalidKind.class);
        private final Map<InvalidKind, Long> fullyMalformedByKind = zeros(InvalidKind.class);
        private final Map<InvalidKind, Long> discarded = zeros(InvalidKind.class);
        private final Map<ProviderId, Long> records = zerosFor(providers);
        private final Map<ProviderId, Long> sameProviderDuplicates = zerosFor(providers);
        private final Map<ProviderId, Long> providerFullyMalformed = zerosFor(providers);
        private final Map<ProviderId, Long> received = zerosFor(providers);
        private final Map<ProviderId, Map<MalformationKind, Long>> malformed = new HashMap<>();

        Counters() {
            providers.forEach(p -> malformed.put(p, zeros(MalformationKind.class)));
        }

        void add(WorldItinerary itinerary) {
            distinct++;
            itinerary.invalidKind().ifPresent(kind -> increment(invalid, kind, 1));
            crossProviderDuplicates += itinerary.providers().size() - 1;

            Map<ProviderId, long[]> wellFormedPerProvider = new HashMap<>();
            long wellFormed = 0;
            for (ProviderRecord record : itinerary.records()) {
                ProviderId provider = record.provider();
                increment(records, provider, 1);
                if (record.copy() > 0) {
                    increment(sameProviderDuplicates, provider, 1);
                }
                long[] count = wellFormedPerProvider.computeIfAbsent(provider, p -> new long[1]);
                record.malformation().ifPresentOrElse(m -> increment(malformed.get(provider), m.kind(), 1), () -> {
                    count[0]++;
                    increment(received, provider, 1);
                });
                if (!record.malformed()) {
                    wellFormed++;
                }
            }
            wellFormedPerProvider.forEach((provider, count) -> {
                if (count[0] == 0) {
                    increment(providerFullyMalformed, provider, 1);
                }
            });

            if (wellFormed == 0) {
                fullyMalformed++;
                itinerary.invalidKind().ifPresent(kind -> increment(fullyMalformedByKind, kind, 1));
            }
            if (itinerary.invalidKind().isPresent()) {
                increment(discarded, itinerary.invalidKind().get(), wellFormed);
            } else if (wellFormed > 0) {
                produced++;
                duplicatesMerged += wellFormed - 1;
            }
        }

        WorldStatistics result() {
            Map<ProviderId, ProviderStatistics> perProvider = new HashMap<>();
            providers.forEach(p -> perProvider.put(
                    p,
                    new ProviderStatistics(
                            records.get(p),
                            sameProviderDuplicates.get(p),
                            providerFullyMalformed.get(p),
                            malformed.get(p))));
            return new WorldStatistics(
                    distinct,
                    invalid,
                    crossProviderDuplicates,
                    perProvider,
                    new FullyMalformedItineraries(fullyMalformed, fullyMalformedByKind),
                    new ExpectedAggregation(received, discarded, duplicatesMerged, produced));
        }
    }

    private static <K> void increment(Map<K, Long> counters, K key, long amount) {
        counters.merge(key, amount, Long::sum);
    }

    private static <E extends Enum<E>> Map<E, Long> zeros(Class<E> type) {
        Map<E, Long> map = new EnumMap<>(type);
        for (E constant : type.getEnumConstants()) {
            map.put(constant, 0L);
        }
        return map;
    }

    private static Map<ProviderId, Long> zerosFor(List<ProviderId> providers) {
        Map<ProviderId, Long> map = new HashMap<>();
        providers.forEach(p -> map.put(p, 0L));
        return map;
    }
}
