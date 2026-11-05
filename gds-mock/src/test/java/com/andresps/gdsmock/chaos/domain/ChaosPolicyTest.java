package com.andresps.gdsmock.chaos.domain;

import static com.andresps.gdsmock.support.WorldFixtures.ALPHA;
import static com.andresps.gdsmock.support.WorldFixtures.BETA;
import static com.andresps.gdsmock.support.WorldFixtures.GAMMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.andresps.gdsmock.chaos.domain.ChaosDecision.Fail;
import com.andresps.gdsmock.chaos.domain.ChaosDecision.Outage;
import com.andresps.gdsmock.chaos.domain.ChaosDecision.Proceed;
import com.andresps.gdsmock.shared.domain.ProviderId;
import com.andresps.gdsmock.shared.domain.UnknownProviderException;
import java.time.Duration;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChaosPolicyTest {

    private static final ChaosSettings DEFAULTS =
            new ChaosSettings(Duration.ofMillis(100), Duration.ofMillis(5_000), 0.10);

    private final RandomGenerator random = mock(RandomGenerator.class);
    private final OutageRegistry outages = new OutageRegistry();

    private ChaosPolicy policy(ChaosSettings alpha) {
        return new ChaosPolicy(Map.of(ALPHA, alpha, BETA, ChaosSettings.NONE), outages, () -> random);
    }

    @ParameterizedTest(name = "draw {0} -> {1} ms")
    @CsvSource({"0, 100", "2400, 2500", "4900, 5000"})
    @DisplayName("AC-15: latency is drawn uniformly in [min, max], both ends included")
    void latencyInRange(long draw, long expectedMillis) {
        when(random.nextLong(4_901)).thenReturn(draw);
        when(random.nextDouble()).thenReturn(0.5);

        ChaosDecision decision = policy(DEFAULTS).decide(ALPHA);

        assertThat(decision).isEqualTo(new Proceed(Duration.ofMillis(expectedMillis)));
        verify(random).nextLong(4_901);
    }

    @Test
    @DisplayName("AC-15: a fixed latency (min = max) needs no random draw")
    void fixedLatency() {
        var fixed = new ChaosSettings(Duration.ofMillis(300), Duration.ofMillis(300), 0.0);
        when(random.nextDouble()).thenReturn(0.5);

        assertThat(policy(fixed).decide(ALPHA)).isEqualTo(new Proceed(Duration.ofMillis(300)));
        verify(random, org.mockito.Mockito.never()).nextLong(anyLong());
    }

    @ParameterizedTest(name = "rate {0}, draw {1} -> fails: {2}")
    @CsvSource({
        "0.10, 0.0999, true",
        "0.10, 0.10, false",
        "0.0, 0.0, false",
        "1.0, 0.9999999, true",
    })
    @DisplayName("AC-16: a request fails with probability error-rate (0 never, 1 always), after the latency")
    void errorRate(double rate, double draw, boolean fails) {
        var settings = new ChaosSettings(Duration.ofMillis(100), Duration.ofMillis(200), rate);
        when(random.nextLong(101)).thenReturn(50L);
        when(random.nextDouble()).thenReturn(draw);

        ChaosDecision decision = policy(settings).decide(ALPHA);

        Duration latency = Duration.ofMillis(150);
        assertThat(decision).isEqualTo(fails ? new Fail(latency) : new Proceed(latency));
    }

    @Test
    @DisplayName("AC-16: with a real random source, about error-rate of the requests fail and latencies stay in range")
    void statisticalBehaviour() {
        var shared = new SplittableRandom(11);
        var policy = new ChaosPolicy(Map.of(ALPHA, DEFAULTS), outages, () -> shared);

        int failures = 0;
        for (int i = 0; i < 10_000; i++) {
            ChaosDecision decision = policy.decide(ALPHA);
            Duration latency = switch (decision) {
                case Proceed p -> p.latency();
                case Fail f -> {
                    failures++;
                    yield f.latency();
                }
                case Outage() -> throw new AssertionError("no outage expected");
            };
            assertThat(latency).isBetween(Duration.ofMillis(100), Duration.ofMillis(5_000));
        }

        assertThat(failures / 10_000.0).isCloseTo(0.10, within(0.01));
    }

    @Test
    @DisplayName("AC-17: a provider in outage fails immediately, without latency or random draws")
    void outageWins() {
        outages.start(ALPHA);

        assertThat(policy(DEFAULTS).decide(ALPHA)).isEqualTo(new Outage());
        verifyNoInteractions(random);
    }

    @Test
    @DisplayName("AC-17: an outage affects only its provider")
    void outageIsPerProvider() {
        outages.start(ALPHA);

        assertThat(policy(DEFAULTS).decide(BETA)).isEqualTo(new Proceed(Duration.ZERO));
    }

    @Test
    @DisplayName("AC-19: ChaosSettings.NONE means no latency and no errors")
    void noChaos() {
        assertThat(ChaosSettings.NONE.latencyMin()).isZero();
        assertThat(ChaosSettings.NONE.latencyMax()).isZero();
        assertThat(ChaosSettings.NONE.errorRate()).isZero();
        assertThat(policy(ChaosSettings.NONE).decide(ALPHA)).isEqualTo(new Proceed(Duration.ZERO));
        verifyNoInteractions(random);
    }

    @Test
    @DisplayName("AC-17: the policy exposes each provider's settings, sorted by id, for the admin listing")
    void exposesSettings() {
        assertThat(policy(DEFAULTS).settings(ALPHA)).isEqualTo(DEFAULTS);
        assertThat(policy(DEFAULTS).providers()).containsExactly(ALPHA, BETA);
    }

    @Test
    @DisplayName("AC-18 (error): an unknown provider is rejected")
    void unknownProvider() {
        assertThatThrownBy(() -> policy(DEFAULTS).decide(GAMMA)).isInstanceOf(UnknownProviderException.class);
        assertThatThrownBy(() -> policy(DEFAULTS).settings(new ProviderId("mock-delta")))
                .isInstanceOf(UnknownProviderException.class);
    }

    @ParameterizedTest(name = "min={0} max={1} rate={2}")
    @CsvSource({"-1, 100, 0.1", "200, 100, 0.1", "0, 100, -0.01", "0, 100, 1.01"})
    @DisplayName("AC-15, AC-16 (error): invalid chaos settings are rejected")
    void invalidSettings(long min, long max, double rate) {
        assertThatThrownBy(() -> new ChaosSettings(Duration.ofMillis(min), Duration.ofMillis(max), rate))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
