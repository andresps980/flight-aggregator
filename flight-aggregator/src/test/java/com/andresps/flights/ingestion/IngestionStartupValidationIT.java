package com.andresps.flights.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.FlightAggregatorApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * AC-23 (error): invalid settings stop the startup with a message naming the property. Settings are passed as
 * command-line arguments, which (unlike builder default properties) override {@code application.yml}.
 */
class IngestionStartupValidationIT {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "aggregator.ingestion.horizon-days=0                                  | aggregator.ingestion.horizon-days",
                "aggregator.ingestion.horizon-days=31                                 | aggregator.ingestion.horizon-days",
                "aggregator.ingestion.max-concurrent-days=0                           | aggregator.ingestion.max-concurrent-days",
                "aggregator.ingestion.max-concurrent-partitions=0                     | aggregator.ingestion.max-concurrent-partitions",
                "aggregator.ingestion.run-timeout=0s                                  | aggregator.ingestion.run-timeout",
                "aggregator.ingestion.markup-percentage=-1                            | aggregator.ingestion.markup-percentage",
                "aggregator.resilience.default.connect-timeout=0s                     | aggregator.resilience.default.connect-timeout",
                "aggregator.resilience.default.read-timeout=-1s                       | aggregator.resilience.default.read-timeout",
                "aggregator.resilience.default.attempt-timeout=5s                     | aggregator.resilience.default.attempt-timeout",
                "aggregator.resilience.default.retry.max-attempts=0                   | aggregator.resilience.default.retry.max-attempts",
                "aggregator.resilience.default.retry.multiplier=0.5                   | aggregator.resilience.default.retry.multiplier",
                "aggregator.resilience.default.retry.jitter=1                         | aggregator.resilience.default.retry.jitter",
                "aggregator.resilience.default.circuit-breaker.failure-rate-threshold=0   | aggregator.resilience.default.circuit-breaker.failure-rate-threshold",
                "aggregator.resilience.default.circuit-breaker.failure-rate-threshold=101 | aggregator.resilience.default.circuit-breaker.failure-rate-threshold",
                "aggregator.resilience.default.circuit-breaker.minimum-calls=0        | aggregator.resilience.default.circuit-breaker.minimum-calls",
                "aggregator.resilience.default.retry.initial-backoff=0s               | aggregator.resilience.default.retry.initial-backoff",
                "aggregator.resilience.default.circuit-breaker.sliding-window=0       | aggregator.resilience.default.circuit-breaker.sliding-window",
                "aggregator.resilience.default.circuit-breaker.open-wait=0s           | aggregator.resilience.default.circuit-breaker.open-wait",
                "aggregator.resilience.default.circuit-breaker.half-open-calls=0      | aggregator.resilience.default.circuit-breaker.half-open-calls",
                "aggregator.resilience.default.max-records-per-partition=0            | aggregator.resilience.default.max-records-per-partition",
                "aggregator.providers.mock-beta.base-url=localhost:8081               | aggregator.providers.mock-beta.base-url",
                "aggregator.providers.mock-beta.base-url=ftp://localhost:8081         | aggregator.providers.mock-beta.base-url",
                "aggregator.providers.mock-beta.resilience.retry.max-attempts=0       | aggregator.providers.mock-beta.resilience.retry.max-attempts",
                "aggregator.providers.mock-delta.base-url=http://localhost:9999       | aggregator.providers.mock-delta",
                "aggregator.providers.mock-beta.base-url=                             | aggregator.providers.mock-beta.base-url",
            })
    @DisplayName("AC-23 (error): invalid settings fail the startup with a message naming the property")
    void invalidSettingsFailStartup(String property, String named) {
        assertThatThrownBy(() -> new SpringApplicationBuilder(FlightAggregatorApplication.class)
                        .web(WebApplicationType.NONE)
                        .run("--" + property)
                        .close())
                .satisfies(e -> assertThat(messages(e)).contains(named));
    }

    private static String messages(Throwable e) {
        var all = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }
}
