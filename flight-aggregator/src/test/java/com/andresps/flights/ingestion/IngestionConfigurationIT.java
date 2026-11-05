package com.andresps.flights.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.ingestion.application.port.out.ProviderFeed;
import com.andresps.flights.ingestion.application.service.IngestionSettings;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.ResilienceSettings;
import com.andresps.flights.ingestion.infrastructure.config.AggregatorProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** AC-23: configuration defaults, per-provider resilience overrides and wiring of one feed per configured provider. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "aggregator.providers.mock-gamma.resilience.read-timeout=9s")
class IngestionConfigurationIT {

    @Autowired
    private AggregatorProperties properties;

    @Autowired
    private List<ProviderFeed> feeds;

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("AC-23: ingestion defaults are 7 days, 5 % markup, 2 days and 2 partitions per provider, 10 min run")
    void ingestionDefaults() {
        assertThat(properties.ingestionSettings()).isEqualTo(new IngestionSettings(7, 2, 2, Duration.ofMinutes(10)));
        assertThat(properties.ingestion().markupPercentage()).isEqualByComparingTo(new BigDecimal("5"));
    }

    @Test
    @DisplayName("AC-23: resilience defaults are the values justified in the spec (ADR-0014)")
    void resilienceDefaults() {
        assertThat(properties.resilienceFor("mock-alpha")).isEqualTo(ResilienceSettings.defaults());
        assertThat(properties.resilienceFor("mock-beta")).isEqualTo(ResilienceSettings.defaults());
    }

    @Test
    @DisplayName("AC-23: a per-provider override changes only that setting of that provider")
    void perProviderOverride() {
        ResilienceSettings gamma = properties.resilienceFor("mock-gamma");

        assertThat(gamma.readTimeout()).isEqualTo(Duration.ofSeconds(9));
        assertThat(gamma.attemptTimeout())
                .isEqualTo(ResilienceSettings.defaults().attemptTimeout());
        assertThat(gamma.retry()).isEqualTo(ResilienceSettings.defaults().retry());
        assertThat(gamma.circuitBreaker())
                .isEqualTo(ResilienceSettings.defaults().circuitBreaker());
    }

    @Test
    @DisplayName("AC-23: the three providers point at gds-mock on localhost:8081 by default and each has a feed")
    void providersAndFeeds() {
        assertThat(properties.providers())
                .containsOnlyKeys("mock-alpha", "mock-beta", "mock-gamma")
                .allSatisfy((id, provider) ->
                        assertThat(provider.baseUrl()).isEqualTo(URI.create("http://localhost:8081")));
        assertThat(feeds)
                .extracting(ProviderFeed::provider)
                .containsExactlyInAnyOrder(
                        new ProviderId("mock-alpha"), new ProviderId("mock-beta"), new ProviderId("mock-gamma"));
    }

    @Test
    @DisplayName("AC-9: the reference date comes from a UTC clock")
    void utcClock() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
