package com.andresps.gdsmock.chaos.domain;

import static com.andresps.gdsmock.support.WorldFixtures.ALPHA;
import static com.andresps.gdsmock.support.WorldFixtures.BETA;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutageRegistryTest {

    private final OutageRegistry outages = new OutageRegistry();

    @Test
    @DisplayName("AC-17: no provider is down until an outage is started")
    void upByDefault() {
        assertThat(outages.isDown(ALPHA)).isFalse();
    }

    @Test
    @DisplayName("AC-17: starting and ending an outage is per provider and idempotent")
    void startAndEnd() {
        outages.start(ALPHA);
        outages.start(ALPHA);

        assertThat(outages.isDown(ALPHA)).isTrue();
        assertThat(outages.isDown(BETA)).isFalse();

        outages.end(ALPHA);
        outages.end(ALPHA);
        outages.end(BETA);

        assertThat(outages.isDown(ALPHA)).isFalse();
    }
}
