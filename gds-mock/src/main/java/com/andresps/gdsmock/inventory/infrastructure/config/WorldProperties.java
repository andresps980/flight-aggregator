package com.andresps.gdsmock.inventory.infrastructure.config;

import com.andresps.gdsmock.inventory.domain.generator.WorldSettings;
import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code gds.world.*}; defaults in {@code application.yml}. Validated by {@link WorldSettings}. */
@ConfigurationProperties("gds.world")
public record WorldProperties(
        long seed,
        int volume,
        int horizonDays,
        LocalDate referenceDate,
        double invalidFraction,
        double malformedFraction,
        double sameProviderDuplicateFraction) {

    WorldSettings toSettings() {
        return new WorldSettings(
                seed,
                volume,
                horizonDays,
                referenceDate,
                invalidFraction,
                malformedFraction,
                sameProviderDuplicateFraction);
    }
}
