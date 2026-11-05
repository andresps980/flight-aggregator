package com.andresps.gdsmock.inventory.domain.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorldSettingsTest {

    private static final LocalDate REFERENCE = LocalDate.of(2026, 11, 5);

    @Test
    @DisplayName("AC-3: defaults are 100,000 itineraries over 7 days, 5% invalid, 1% malformed, 0.5% duplicates")
    void defaults() {
        WorldSettings defaults = WorldSettings.defaults();

        assertThat(defaults.volume()).isEqualTo(100_000);
        assertThat(defaults.horizonDays()).isEqualTo(7);
        assertThat(defaults.referenceDate()).isNull();
        assertThat(defaults.invalidFraction()).isEqualTo(0.05);
        assertThat(defaults.malformedFraction()).isEqualTo(0.01);
        assertThat(defaults.sameProviderDuplicateFraction()).isEqualTo(0.005);
    }

    @ParameterizedTest(name = "volume={0}, horizonDays={1}")
    @CsvSource({"1, 1", "1000000, 7", "1000000, 30", "239976, 1"})
    @DisplayName("AC-3: accepts volumes 1..1,000,000 and horizons 1..30 days within the per-day limit")
    void acceptsBounds(int volume, int horizonDays) {
        assertThatCode(() -> new WorldSettings(42, volume, horizonDays, REFERENCE, 0.05, 0.01, 0.005))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "volume={0}, horizonDays={1} -> {2}")
    @CsvSource({
        "0, 7, volume",
        "1000001, 7, volume",
        "100, 0, horizon-days",
        "100, 31, horizon-days",
        "239977, 1, per day",
        "1000000, 4, per day"
    })
    @DisplayName("AC-3 (error): rejects out-of-range volume and horizon with a message naming the setting")
    void rejectsOutOfRangeVolumeAndHorizon(int volume, int horizonDays, String named) {
        assertThatThrownBy(() -> new WorldSettings(42, volume, horizonDays, REFERENCE, 0.05, 0.01, 0.005))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(named);
    }

    @ParameterizedTest(name = "invalid={0}, malformed={1}, duplicates={2} -> {3}")
    @CsvSource({
        "-0.01, 0.01, 0.005, invalid-fraction",
        "1.01, 0.01, 0.005, invalid-fraction",
        "0.05, -0.1, 0.005, malformed-fraction",
        "0.05, 1.5, 0.005, malformed-fraction",
        "0.05, 0.01, -0.001, same-provider-duplicate-fraction",
        "0.05, 0.01, 1.1, same-provider-duplicate-fraction"
    })
    @DisplayName("AC-3 (error): rejects fractions outside [0, 1] with a message naming the setting")
    void rejectsFractionsOutOfRange(double invalid, double malformed, double duplicates, String named) {
        assertThatThrownBy(() -> new WorldSettings(42, 100, 7, REFERENCE, invalid, malformed, duplicates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(named);
    }
}
