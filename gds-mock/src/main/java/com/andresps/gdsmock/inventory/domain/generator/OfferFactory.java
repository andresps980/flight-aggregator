package com.andresps.gdsmock.inventory.domain.generator;

import com.andresps.gdsmock.inventory.domain.model.Airport;
import com.andresps.gdsmock.inventory.domain.model.Malformation;
import com.andresps.gdsmock.inventory.domain.model.MalformationKind;
import com.andresps.gdsmock.inventory.domain.model.ProviderRecord;
import com.andresps.gdsmock.inventory.domain.model.ScheduledSegment;
import com.andresps.gdsmock.shared.domain.ProviderId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * Decides which providers offer an itinerary and builds their records: an independent price per provider, optional
 * same-provider duplicate and optional malformation per record (spec 002, AC-4 and AC-7).
 */
final class OfferFactory {

    private static final BigDecimal MIN_PRICE = new BigDecimal("1.00");
    private static final MalformationKind[] MALFORMATIONS = MalformationKind.values();

    private final List<ProviderId> providers;
    private final double malformedFraction;
    private final double sameProviderDuplicateFraction;

    OfferFactory(List<ProviderId> providers, double malformedFraction, double sameProviderDuplicateFraction) {
        this.providers = List.copyOf(providers);
        this.malformedFraction = malformedFraction;
        this.sameProviderDuplicateFraction = sameProviderDuplicateFraction;
    }

    List<ProviderRecord> records(
            SplittableRandom random, String itineraryRef, List<ScheduledSegment> segments, BigDecimal baseFare) {
        List<ProviderRecord> records = new ArrayList<>();
        for (ProviderId provider : offeringProviders(random)) {
            BigDecimal price = vary(baseFare, 0.90, 0.25, random);
            records.add(record(random, provider, itineraryRef, 0, segments, price));
            if (random.nextDouble() < sameProviderDuplicateFraction) {
                BigDecimal other = vary(price, 0.95, 0.15, random);
                if (other.compareTo(price) == 0) {
                    other = other.add(new BigDecimal("0.01"));
                }
                records.add(record(random, provider, itineraryRef, 1, segments, other));
            }
        }
        return records;
    }

    /** 1, 2 or 3 providers with probability 40 / 35 / 25 % (capped by the number of providers), in provider order. */
    private List<ProviderId> offeringProviders(SplittableRandom random) {
        double roll = random.nextDouble();
        int count = Math.min(roll < 0.40 ? 1 : roll < 0.75 ? 2 : 3, providers.size());
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < providers.size(); i++) {
            pool.add(i);
        }
        List<Integer> picked = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            picked.add(pool.remove(random.nextInt(pool.size())));
        }
        return picked.stream().sorted().map(providers::get).toList();
    }

    private ProviderRecord record(
            SplittableRandom random,
            ProviderId provider,
            String itineraryRef,
            int copy,
            List<ScheduledSegment> segments,
            BigDecimal price) {
        Optional<Malformation> malformation = Optional.empty();
        List<ScheduledSegment> recordSegments = segments;
        BigDecimal recordPrice = price;
        if (random.nextDouble() < malformedFraction) {
            Malformation m = new Malformation(
                    MALFORMATIONS[random.nextInt(MALFORMATIONS.length)], random.nextInt(segments.size()));
            malformation = Optional.of(m);
            switch (m.kind()) {
                case BAD_AIRPORT_CODE -> recordSegments = withCorruptedOrigin(segments, m.segmentIndex(), random);
                case NON_POSITIVE_PRICE ->
                    recordPrice = random.nextBoolean() ? BigDecimal.ZERO.setScale(2) : price.negate();
                case UNPARSEABLE_TIME -> {
                    // rendered by each provider format, the data stays intact
                }
            }
        }
        return new ProviderRecord(provider, itineraryRef + "-" + copy, copy, recordSegments, recordPrice, malformation);
    }

    /** Replaces the middle letter of the segment's origin code with a digit (e.g. {@code MAD} → {@code M4D}). */
    private static List<ScheduledSegment> withCorruptedOrigin(
            List<ScheduledSegment> segments, int index, SplittableRandom random) {
        List<ScheduledSegment> corrupted = new ArrayList<>(segments);
        ScheduledSegment s = segments.get(index);
        String code = s.origin().code();
        String bad = code.charAt(0) + String.valueOf(random.nextInt(10)) + code.charAt(2);
        corrupted.set(
                index,
                new ScheduledSegment(
                        s.airline(),
                        s.flightNumber(),
                        new Airport(bad, s.origin().zone()),
                        s.destination(),
                        s.departure(),
                        s.arrival()));
        return corrupted;
    }

    /** {@code amount × (low + spread × u)}, u uniform in [0, 1), scale 2, at least 1.00. */
    private static BigDecimal vary(BigDecimal amount, double low, double spread, SplittableRandom random) {
        BigDecimal factor = BigDecimal.valueOf(low + spread * random.nextDouble());
        BigDecimal varied = amount.multiply(factor).setScale(2, RoundingMode.HALF_EVEN);
        return varied.max(MIN_PRICE);
    }
}
