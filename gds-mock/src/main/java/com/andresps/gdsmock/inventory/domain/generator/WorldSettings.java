package com.andresps.gdsmock.inventory.domain.generator;

import java.time.LocalDate;

/**
 * Settings of the generated world (spec 002, AC-3). Messages name the configuration property under {@code gds.world}.
 *
 * @param seed seeds every random choice; same seed + reference date + settings ⇒ same data
 * @param volume distinct itineraries over the whole horizon, 1..1,000,000
 * @param horizonDays days served after the reference date, 1..30
 * @param referenceDate fixed reference date, or {@code null} for today (UTC) from the clock
 * @param invalidFraction share of business-invalid itineraries, 0..1
 * @param malformedFraction share of malformed provider records, 0..1
 * @param sameProviderDuplicateFraction share of records duplicated by the same provider, 0..1
 */
public record WorldSettings(
        long seed,
        int volume,
        int horizonDays,
        LocalDate referenceDate,
        double invalidFraction,
        double malformedFraction,
        double sameProviderDuplicateFraction) {

    public static final int MAX_VOLUME = 1_000_000;
    public static final int MAX_HORIZON_DAYS = 30;
    /** Each itinerary of a day gets a unique (airline, first flight number): 24 airlines × 9,999 numbers. */
    public static final int MAX_ITINERARIES_PER_DAY = Catalog.AIRLINES.size() * Catalog.MAX_FLIGHT_NUMBER;

    public WorldSettings {
        if (volume < 1 || volume > MAX_VOLUME) {
            throw new IllegalArgumentException(
                    "volume must be between 1 and %d (was %d)".formatted(MAX_VOLUME, volume));
        }
        if (horizonDays < 1 || horizonDays > MAX_HORIZON_DAYS) {
            throw new IllegalArgumentException(
                    "horizon-days must be between 1 and %d (was %d)".formatted(MAX_HORIZON_DAYS, horizonDays));
        }
        int perDay = Math.ceilDiv(volume, horizonDays);
        if (perDay > MAX_ITINERARIES_PER_DAY) {
            throw new IllegalArgumentException(
                    "volume per day (volume / horizon-days, rounded up) must be at most %d (was %d)"
                            .formatted(MAX_ITINERARIES_PER_DAY, perDay));
        }
        requireFraction("invalid-fraction", invalidFraction);
        requireFraction("malformed-fraction", malformedFraction);
        requireFraction("same-provider-duplicate-fraction", sameProviderDuplicateFraction);
    }

    /** 100,000 itineraries over 7 days from today, 5 % invalid, 1 % malformed, 0.5 % same-provider duplicates. */
    public static WorldSettings defaults() {
        return new WorldSettings(42, 100_000, 7, null, 0.05, 0.01, 0.005);
    }

    private static void requireFraction(String name, double value) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException("%s must be between 0 and 1 (was %s)".formatted(name, value));
        }
    }
}
