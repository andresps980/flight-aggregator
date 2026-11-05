package com.andresps.gdsmock.chaos.domain;

import com.andresps.gdsmock.shared.domain.ProviderId;
import com.andresps.gdsmock.shared.domain.UnknownProviderException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Decides latency, injected errors and outages per provider request. Randomness is injected, so tests can control it;
 * in production it is a per-thread generator, deliberately not seeded by the world seed (chaos must never affect
 * data).
 */
public final class ChaosPolicy {

    private final Map<ProviderId, ChaosSettings> settings;
    private final OutageRegistry outages;
    private final Supplier<RandomGenerator> random;

    /** @param random supplies the generator for the calling thread (e.g. {@code ThreadLocalRandom::current}) */
    public ChaosPolicy(
            Map<ProviderId, ChaosSettings> settings, OutageRegistry outages, Supplier<RandomGenerator> random) {
        this.settings = Map.copyOf(settings);
        this.outages = outages;
        this.random = random;
    }

    /**
     * Outage first (no latency, no draw); otherwise latency uniform in [min, max] ms, then an error with probability
     * {@code errorRate}. No random draw is made when the latency is fixed or the error rate is 0.
     */
    public ChaosDecision decide(ProviderId provider) {
        ChaosSettings chaos = settings(provider);
        if (outages.isDown(provider)) {
            return new ChaosDecision.Outage();
        }
        long minMillis = chaos.latencyMin().toMillis();
        long rangeMillis = chaos.latencyMax().toMillis() - minMillis;
        Duration latency =
                Duration.ofMillis(rangeMillis > 0 ? minMillis + random.get().nextLong(rangeMillis + 1) : minMillis);
        boolean fails = chaos.errorRate() > 0 && random.get().nextDouble() < chaos.errorRate();
        return fails ? new ChaosDecision.Fail(latency) : new ChaosDecision.Proceed(latency);
    }

    public ChaosSettings settings(ProviderId provider) {
        ChaosSettings chaos = settings.get(provider);
        if (chaos == null) {
            throw new UnknownProviderException(provider);
        }
        return chaos;
    }

    /** Providers with chaos settings, sorted by id. */
    public List<ProviderId> providers() {
        return settings.keySet().stream().sorted().toList();
    }
}
