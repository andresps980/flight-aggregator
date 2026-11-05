package com.andresps.flights.aggregation.domain;

import com.andresps.flights.aggregation.domain.model.AirportCode;
import com.andresps.flights.aggregation.domain.model.FlightNumber;
import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.Itinerary;
import com.andresps.flights.aggregation.domain.model.Money;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.model.Segment;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Readable builders for test data. Times are written as {@code "2026-11-10T08:00"} and read as UTC. The fixed clock is
 * a few days before every fixture flight, so fixtures are "in the future" unless a test says otherwise.
 */
public final class FlightFixtures {

    public static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    public static final ProviderId ALPHA = new ProviderId("mock-alpha");
    public static final ProviderId BETA = new ProviderId("mock-beta");
    public static final ProviderId GAMMA = new ProviderId("mock-gamma");

    private FlightFixtures() {}

    public static Instant at(String utcDateTime) {
        return Instant.parse(utcDateTime + ":00Z");
    }

    public static Segment segment(String flight, String origin, String destination, String departure, String arrival) {
        return segment(flight, origin, destination, at(departure), at(arrival));
    }

    public static Segment segment(
            String flight, String origin, String destination, Instant departure, Instant arrival) {
        return new Segment(
                FlightNumber.parse(flight), new AirportCode(origin), new AirportCode(destination), departure, arrival);
    }

    public static Itinerary itinerary(Segment... segments) {
        return new Itinerary(List.of(segments));
    }

    /** Valid direct flight MAD → LHR. */
    public static Itinerary direct() {
        return itinerary(segment("IB1001", "MAD", "LHR", "2026-11-10T07:00", "2026-11-10T09:30"));
    }

    /** Valid MAD → BCN → LHR with a 75-minute connection. */
    public static Itinerary madBcnLhr() {
        return itinerary(
                segment("IB3456", "MAD", "BCN", "2026-11-10T08:00", "2026-11-10T09:15"),
                segment("IB3457", "BCN", "LHR", "2026-11-10T10:30", "2026-11-10T12:30"));
    }

    /** MAD → BCN → LHR (IB3456 / IB3457) where the second departure leaves {@code connection} after the first arrival. */
    public static Itinerary twoLegsWithConnection(Duration connection) {
        Instant firstArrival = at("2026-11-10T09:15");
        Instant secondDeparture = firstArrival.plus(connection);
        return itinerary(
                segment("IB3456", "MAD", "BCN", at("2026-11-10T08:00"), firstArrival),
                segment("IB3457", "BCN", "LHR", secondDeparture, secondDeparture.plus(Duration.ofHours(2))));
    }

    public static FlightOffer offer(ProviderId provider, String basePrice, Itinerary itinerary) {
        return new FlightOffer(itinerary, provider, Money.eur(basePrice));
    }
}
