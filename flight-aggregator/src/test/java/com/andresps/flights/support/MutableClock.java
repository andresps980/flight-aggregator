package com.andresps.flights.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand (breaker open wait, staleness, retention) without sleeping. Thread-safe. */
public final class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock() {
        this(Instant.parse("2026-11-05T12:00:00Z"));
    }

    public MutableClock(Instant start) {
        this.now = start;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
