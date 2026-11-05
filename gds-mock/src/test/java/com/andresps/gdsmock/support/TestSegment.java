package com.andresps.gdsmock.support;

import java.time.Instant;

/**
 * Format-neutral view of a segment, used by tests to check spec 001 rules without depending on the aggregator.
 *
 * @param departure {@code null} when the provider sent an unparseable time
 * @param arrival {@code null} when the provider sent an unparseable time
 */
public record TestSegment(
        String flight, String airline, String origin, String destination, Instant departure, Instant arrival) {}
