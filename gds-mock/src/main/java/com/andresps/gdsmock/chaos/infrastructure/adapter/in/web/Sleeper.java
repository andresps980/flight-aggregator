package com.andresps.gdsmock.chaos.infrastructure.adapter.in.web;

import java.time.Duration;

/** Waits; replaced by a recording fake in tests so latency is checked without real sleeping. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;
}
