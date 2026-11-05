package com.andresps.flights.aggregation.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Ordered, non-empty list of segments. Whether the segments form a sellable trip (chaining, connection times…) is
 * decided by the validation rules, not here, so that invalid trips can be counted with a reason.
 */
public record Itinerary(List<Segment> segments) {

    public Itinerary {
        InvalidFlightDataException.requireField(segments, "Itinerary segments");
        if (segments.isEmpty()) {
            throw new InvalidFlightDataException("An itinerary needs at least one segment");
        }
        if (segments.stream().anyMatch(Objects::isNull)) {
            throw new InvalidFlightDataException("An itinerary cannot contain a missing segment");
        }
        segments = List.copyOf(segments);
    }

    /** Identity used for deduplication: flight numbers, airports and departure times (ADR-0011). */
    public ItineraryKey key() {
        return new ItineraryKey(segments.stream()
                .map(Itinerary::identityOf)
                .collect(Collectors.joining(ItineraryKey.SEGMENT_SEPARATOR)));
    }

    /** Full, deterministic representation including arrival times; the last tie-break between offers. */
    public String canonicalForm() {
        return segments.stream()
                .map(segment -> identityOf(segment) + ">" + segment.arrival())
                .collect(Collectors.joining(ItineraryKey.SEGMENT_SEPARATOR));
    }

    public Instant firstDeparture() {
        return segments.getFirst().departure();
    }

    /** Time between each arrival and the next departure; empty for a direct flight. May be negative. */
    public List<Duration> connectionTimes() {
        List<Duration> connections = new ArrayList<>(segments.size() - 1);
        for (int i = 1; i < segments.size(); i++) {
            connections.add(Duration.between(
                    segments.get(i - 1).arrival(), segments.get(i).departure()));
        }
        return Collections.unmodifiableList(connections);
    }

    /** The first origin followed by every segment destination, in travel order. */
    public List<AirportCode> visitedAirports() {
        List<AirportCode> airports = new ArrayList<>(segments.size() + 1);
        airports.add(segments.getFirst().origin());
        segments.forEach(segment -> airports.add(segment.destination()));
        return Collections.unmodifiableList(airports);
    }

    private static String identityOf(Segment segment) {
        return segment.flightNumber() + ":" + segment.origin() + "-" + segment.destination() + "@"
                + segment.departure();
    }
}
