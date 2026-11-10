package com.andresps.gdsmock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * AC-3: settings out of range stop the application at startup with a message naming the property. Settings are
 * passed as command-line arguments, which (unlike builder default properties) override {@code application.yml}.
 */
class StartupValidationIT {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "gds.world.volume=0, gds.world.volume",
        "gds.world.volume=1000001, gds.world.volume",
        "gds.world.horizon-days=31, gds.world.horizon-days",
        "gds.world.invalid-fraction=1.5, gds.world.invalid-fraction",
        "gds.chaos.default.error-rate=2, error-rate",
        "gds.chaos.providers.mock-beta.latency-min=9s, providers.mock-beta",
        "gds.chaos.providers.mock-btea.error-rate=0.5, unknown provider",
        "gds.chaos.providers.mockbeta.error-rate=0.5, gds.chaos.providers.mockbeta",
        // spec 008 AC-14: currencies must be ISO 4217 codes with a rate; rates must be positive, for ISO codes
        "'gds.providers.mock-beta.currencies=EUR,XYZ', gds.providers.mock-beta.currencies",
        "gds.providers.mock-beta.currencies=CHF, gds.providers.mock-beta.currencies",
        "gds.providers.mock-beta.currencies=, gds.providers.mock-beta.currencies",
        "gds.providers.mock-btea.currencies=USD, gds.providers.mock-btea",
        "gds.fx.rates.USD=0, gds.fx.rates.USD",
        "gds.fx.rates.ZZZ=1.5, gds.fx.rates.ZZZ"
    })
    @DisplayName("AC-3 (error): invalid settings fail the startup with a message naming the setting")
    void invalidSettingsFailStartup(String property, String named) {
        assertThatThrownBy(() -> new SpringApplicationBuilder(GdsMockApplication.class)
                        .profiles("deterministic")
                        .run("--server.port=0", "--" + property)
                        .close())
                .satisfies(e -> assertThat(rootCauseMessages(e)).contains(named));
    }

    private static String rootCauseMessages(Throwable e) {
        var messages = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
