package com.andresps.gdsmock.support;

import com.andresps.gdsmock.inventory.domain.generator.WorldGenerator;
import com.andresps.gdsmock.inventory.domain.generator.WorldSettings;
import com.andresps.gdsmock.shared.domain.ProviderId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/** Shared test settings: a fixed reference date, the three provider ids and small builders. */
public final class WorldFixtures {

    public static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 11, 5);
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC);
    /** "Now" for the aggregator in reconciliation checks: during the reference date, before the horizon. */
    public static final Instant AGGREGATION_NOW = Instant.parse("2026-11-05T12:00:00Z");

    public static final ProviderId ALPHA = new ProviderId("mock-alpha");
    public static final ProviderId BETA = new ProviderId("mock-beta");
    public static final ProviderId GAMMA = new ProviderId("mock-gamma");
    public static final List<ProviderId> PROVIDERS = List.of(ALPHA, BETA, GAMMA);

    private WorldFixtures() {}

    /** Default fractions (5 % invalid, 1 % malformed, 0.5 % same-provider duplicates), seed 7, 7 days. */
    public static WorldSettings settings(int volume) {
        return settings(7L, volume, 0.05, 0.01, 0.005);
    }

    public static WorldSettings settings(
            long seed, int volume, double invalid, double malformed, double sameProviderDuplicates) {
        return new WorldSettings(seed, volume, 7, REFERENCE_DATE, invalid, malformed, sameProviderDuplicates);
    }

    public static WorldGenerator generator(WorldSettings settings) {
        return new WorldGenerator(settings, PROVIDERS, CLOCK);
    }
}
