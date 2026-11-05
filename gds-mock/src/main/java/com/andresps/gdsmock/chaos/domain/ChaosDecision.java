package com.andresps.gdsmock.chaos.domain;

import java.time.Duration;

/** What happens to one provider request. */
public sealed interface ChaosDecision {

    /** Wait {@code latency}, then serve the feed. */
    record Proceed(Duration latency) implements ChaosDecision {}

    /** Wait {@code latency}, then fail with an injected {@code 500}. */
    record Fail(Duration latency) implements ChaosDecision {}

    /** The provider is in a forced outage: fail immediately with {@code 503}. */
    record Outage() implements ChaosDecision {}
}
