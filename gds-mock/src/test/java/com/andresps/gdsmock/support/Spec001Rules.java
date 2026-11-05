package com.andresps.gdsmock.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * Test-local re-implementation of spec 001's validation rules and evaluation order (AC-13 – AC-20 there).
 *
 * <p>gds-mock must not depend on flight-aggregator, so the rules are restated here to check that the generated data
 * breaks exactly the rule it claims to break. Returns the name of the first failing rule, as spec 001 counts it.
 */
public final class Spec001Rules {

    private static final Duration MIN_CONNECTION = Duration.ofMinutes(45);
    private static final Duration MAX_LAYOVER = Duration.ofHours(24);

    private Spec001Rules() {}

    public static Optional<String> firstFailure(List<TestSegment> segments, Instant now) {
        if (segments.stream().map(TestSegment::airline).distinct().count() > 1) {
            return Optional.of("MIXED_AIRLINES");
        }
        if (segments.stream().anyMatch(s -> !s.arrival().isAfter(s.departure()))) {
            return Optional.of("ARRIVAL_NOT_AFTER_DEPARTURE");
        }
        for (int i = 1; i < segments.size(); i++) {
            if (!segments.get(i).origin().equals(segments.get(i - 1).destination())) {
                return Optional.of("SEGMENTS_NOT_CHAINED");
            }
        }
        List<String> visited = new ArrayList<>();
        visited.add(segments.getFirst().origin());
        segments.forEach(s -> visited.add(s.destination()));
        if (new HashSet<>(visited).size() != visited.size()) {
            return Optional.of("REVISITED_AIRPORT");
        }
        for (Duration connection : connections(segments)) {
            if (connection.compareTo(MIN_CONNECTION) < 0) {
                return Optional.of("CONNECTION_TOO_SHORT");
            }
        }
        for (Duration connection : connections(segments)) {
            if (connection.compareTo(MAX_LAYOVER) > 0) {
                return Optional.of("LAYOVER_TOO_LONG");
            }
        }
        if (!segments.getFirst().departure().isAfter(now)) {
            return Optional.of("ALREADY_DEPARTED");
        }
        return Optional.empty();
    }

    public static List<Duration> connections(List<TestSegment> segments) {
        List<Duration> connections = new ArrayList<>();
        for (int i = 1; i < segments.size(); i++) {
            connections.add(Duration.between(
                    segments.get(i - 1).arrival(), segments.get(i).departure()));
        }
        return connections;
    }
}
