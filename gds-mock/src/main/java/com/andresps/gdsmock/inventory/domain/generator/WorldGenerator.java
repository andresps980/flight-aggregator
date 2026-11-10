package com.andresps.gdsmock.inventory.domain.generator;

import com.andresps.gdsmock.inventory.domain.model.Horizon;
import com.andresps.gdsmock.inventory.domain.model.InvalidKind;
import com.andresps.gdsmock.inventory.domain.model.ProviderRecord;
import com.andresps.gdsmock.inventory.domain.model.ScheduledSegment;
import com.andresps.gdsmock.inventory.domain.model.WorldItinerary;
import com.andresps.gdsmock.shared.domain.ProviderId;
import com.andresps.gdsmock.shared.domain.UnknownProviderException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Generates the world lazily and deterministically (ADR-0013).
 *
 * <p>Itinerary {@code i} of partition day {@code d} is built from its own {@code SplittableRandom} seeded with
 * {@code (seed, d, i)}: it doesn't depend on any other itinerary, so a day or a provider can be generated on its own
 * and every provider sees the same itinerary. Within a day, index {@code i} maps one-to-one to the first segment's
 * (airline, flight number), which makes identities unique by construction: across days the departure dates differ,
 * and departed itineraries (which all depart the day before the reference date) use partition-specific departure
 * slots. Nothing is cached: memory doesn't grow
 * with the volume.
 */
public final class WorldGenerator {

    private static final InvalidKind[] INVALID_KINDS = InvalidKind.values();
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final WorldSettings settings;
    private final List<ProviderId> providers;
    private final ProviderCurrencies currencies;
    private final Clock clock;
    private final ScheduleFactory schedules = new ScheduleFactory();
    private final OfferFactory offers;

    /**
     * @param providers the providers that can offer itineraries; their order is the record order
     * @param clock gives today's date (UTC) when the settings have no reference date
     */
    public WorldGenerator(WorldSettings settings, List<ProviderId> providers, Clock clock) {
        this(settings, providers, ProviderCurrencies.eurOnly(), clock);
    }

    /**
     * @param currencies what each provider prices in (spec 008); the draw doesn't change the world, only how its
     *     records are priced
     */
    public WorldGenerator(
            WorldSettings settings, List<ProviderId> providers, ProviderCurrencies currencies, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.currencies = Objects.requireNonNull(currencies, "currencies");
        this.providers = List.copyOf(providers);
        this.clock = Objects.requireNonNull(clock, "clock");
        if (this.providers.isEmpty()) {
            throw new IllegalArgumentException("At least one provider is required");
        }
        this.offers = new OfferFactory(
                this.providers, settings.malformedFraction(), settings.sameProviderDuplicateFraction());
    }

    public Horizon horizon() {
        LocalDate reference = settings.referenceDate() != null
                ? settings.referenceDate()
                : LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return Horizon.after(reference, settings.horizonDays());
    }

    /** The whole world, day by day, in generation order. */
    public Stream<WorldItinerary> itineraries() {
        return itineraries(horizon());
    }

    /** The whole world of a horizon the caller already read. */
    public Stream<WorldItinerary> itineraries(Horizon horizon) {
        return horizon.days().stream().flatMap(day -> day(horizon, day));
    }

    /** One partition day of the world. */
    public Stream<WorldItinerary> itineraries(LocalDate day) {
        return itineraries(horizon(), day);
    }

    /** One partition day of a horizon the caller already read. */
    public Stream<WorldItinerary> itineraries(Horizon horizon, LocalDate day) {
        if (!horizon.contains(day)) {
            throw new DateOutsideHorizonException(day, horizon);
        }
        return day(horizon, day);
    }

    /** One provider's records of the whole world, in generation order. */
    public Stream<ProviderRecord> records(ProviderId provider) {
        requireKnown(provider);
        return recordsOf(itineraries(), provider);
    }

    /** One provider's records of one partition day, in generation order. */
    public Stream<ProviderRecord> records(ProviderId provider, LocalDate day) {
        requireKnown(provider);
        return recordsOf(itineraries(day), provider);
    }

    public List<ProviderId> providers() {
        return providers;
    }

    private static Stream<ProviderRecord> recordsOf(Stream<WorldItinerary> itineraries, ProviderId provider) {
        return itineraries
                .flatMap(i -> i.records().stream())
                .filter(r -> r.provider().equals(provider));
    }

    private void requireKnown(ProviderId provider) {
        if (!providers.contains(provider)) {
            throw new UnknownProviderException(provider);
        }
    }

    private Stream<WorldItinerary> day(Horizon horizon, LocalDate day) {
        int dayIndex = (int) (day.toEpochDay() - horizon.from().toEpochDay());
        int count = settings.volume() / settings.horizonDays()
                + (dayIndex < settings.volume() % settings.horizonDays() ? 1 : 0);
        return IntStream.range(0, count).mapToObj(index -> itinerary(horizon.referenceDate(), day, dayIndex, index));
    }

    private WorldItinerary itinerary(LocalDate referenceDate, LocalDate day, int dayIndex, int index) {
        long seed = seed(day, index);
        SplittableRandom random = new SplittableRandom(seed);
        String airline = Catalog.AIRLINES.get(index % Catalog.AIRLINES.size());
        int flightNumber = index / Catalog.AIRLINES.size() + 1;
        Optional<InvalidKind> kind = random.nextDouble() < settings.invalidFraction()
                ? Optional.of(INVALID_KINDS[random.nextInt(INVALID_KINDS.length)])
                : Optional.empty();
        List<ScheduledSegment> segments = schedules.build(
                random, day, dayIndex, settings.horizonDays(), referenceDate, airline, flightNumber, kind);
        BigDecimal baseFare = BigDecimal.valueOf(3_000L + random.nextInt(40_000) + 6_000L * (segments.size() - 1), 2);
        String ref = "%s-%06d".formatted(DAY.format(day), index);
        List<ProviderRecord> records = offers.records(random, ref, segments, baseFare).stream()
                .map(record -> currencies.price(record, currencySeed(seed, record)))
                .toList();
        return new WorldItinerary(day, index, segments, baseFare, kind, records);
    }

    /**
     * Spec 008: a seed of its own for each record's currency draw, from the itinerary's seed, the provider and the copy,
     * so the generation stream (and with it the world) is the same whatever the currencies.
     */
    private static long currencySeed(long itinerarySeed, ProviderRecord record) {
        long h = mix(itinerarySeed ^ 0xC2B2AE3D27D4EB4FL);
        h = mix(h ^ record.provider().value().hashCode());
        return mix(h ^ record.copy());
    }

    private long seed(LocalDate day, int index) {
        long h = mix(settings.seed() ^ 0x9E3779B97F4A7C15L);
        h = mix(h ^ day.toEpochDay());
        return mix(h ^ index);
    }

    /** SplitMix64 finaliser: spreads close inputs (consecutive days and indexes) over unrelated seeds. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
