package com.andresps.gdsmock.chaos.infrastructure.config;

import com.andresps.gdsmock.chaos.domain.ChaosSettings;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

/**
 * {@code gds.chaos.default.*} applies to every provider; {@code gds.chaos.providers.<id>.*} overrides any of its
 * values for one provider. Defaults in {@code application.yml}; the {@code deterministic} profile turns chaos off.
 */
@ConfigurationProperties("gds.chaos")
public record ChaosProperties(@Name("default") Values defaults, Map<String, Values> providers) {

    /** Any value may be left out of a provider override. */
    public record Values(Duration latencyMin, Duration latencyMax, Double errorRate) {}

    public ChaosProperties {
        defaults = defaults == null ? new Values(null, null, null) : defaults;
        providers = providers == null ? Map.of() : Map.copyOf(providers);
    }

    ChaosSettings settingsFor(String providerId) {
        Values override = providers.getOrDefault(providerId, new Values(null, null, null));
        return new ChaosSettings(
                first(override.latencyMin(), defaults.latencyMin(), Duration.ZERO),
                first(override.latencyMax(), defaults.latencyMax(), Duration.ZERO),
                first(override.errorRate(), defaults.errorRate(), 0.0));
    }

    private static <T> T first(T override, T defaultValue, T fallback) {
        return override != null ? override : defaultValue != null ? defaultValue : fallback;
    }
}
