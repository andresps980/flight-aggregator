package com.andresps.gdsmock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** AC-3: settings out of range stop the application at startup with a message naming the property. */
class StartupValidationIT {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "gds.world.volume=0, volume",
        "gds.world.volume=1000001, volume",
        "gds.world.horizon-days=31, horizon-days",
        "gds.world.invalid-fraction=1.5, invalid-fraction",
        "gds.chaos.default.error-rate=2, error-rate",
        "gds.chaos.providers.mock-beta.latency-min=9s, latency"
    })
    @DisplayName("AC-3 (error): invalid settings fail the startup with a message naming the setting")
    void invalidSettingsFailStartup(String property, String named) {
        assertThatThrownBy(() -> new SpringApplicationBuilder(GdsMockApplication.class)
                        .profiles("deterministic")
                        .properties("server.port=0", property)
                        .run()
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
