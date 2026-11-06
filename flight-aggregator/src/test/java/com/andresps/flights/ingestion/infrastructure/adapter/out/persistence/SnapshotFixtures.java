package com.andresps.flights.ingestion.infrastructure.adapter.out.persistence;

import com.andresps.flights.aggregation.domain.deduplication.AggregatedItinerary;
import com.andresps.flights.aggregation.domain.model.AirportCode;
import com.andresps.flights.aggregation.domain.model.FlightNumber;
import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.Itinerary;
import com.andresps.flights.aggregation.domain.model.Money;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.model.Segment;
import com.andresps.flights.ingestion.application.port.out.DaySnapshotWrite;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.IntStream;

/** Aggregated itineraries and snapshot writes for persistence tests, unique by index within a day. */
public final class SnapshotFixtures {

    public static final ProviderId ALPHA = new ProviderId("mock-alpha");
    public static final ProviderId BETA = new ProviderId("mock-beta");
    public static final ProviderId GAMMA = new ProviderId("mock-gamma");
    public static final SortedSet<ProviderId> ALL = providers(ALPHA, BETA, GAMMA);

    private SnapshotFixtures() {}

    public static SortedSet<ProviderId> providers(ProviderId... ids) {
        return new TreeSet<>(Arrays.asList(ids));
    }

    /**
     * Itinerary {@code index} of {@code day}: a direct MAD → BCN flight, or MAD → BCN → LHR every third index, with a
     * departure minute and flight number derived from the index so that keys are unique for up to 1440 × 9999.
     */
    public static AggregatedItinerary itinerary(LocalDate day, int index) {
        Instant departure = day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(60L * (index % 1440));
        String flight = "IB" + (index / 1440 + 1);
        Segment first = new Segment(
                FlightNumber.parse(flight),
                new AirportCode("MAD"),
                new AirportCode("BCN"),
                departure,
                departure.plusSeconds(4500));
        List<Segment> segments = index % 3 == 0
                ? List.of(
                        first,
                        new Segment(
                                FlightNumber.parse("IB" + (5000 + index / 1440)),
                                new AirportCode("BCN"),
                                new AirportCode("LHR"),
                                departure.plusSeconds(9000),
                                departure.plusSeconds(16200)))
                : List.of(first);
        BigDecimal base = BigDecimal.valueOf(5000 + index % 90000, 2);
        FlightOffer offer = new FlightOffer(new Itinerary(segments), ALPHA, Money.eur(base));
        return new AggregatedItinerary(
                offer.itinerary().key(),
                offer,
                Money.eur(base.multiply(new BigDecimal("1.05"))),
                index % 2 == 0 ? providers(ALPHA, BETA) : providers(ALPHA));
    }

    public static List<AggregatedItinerary> itineraries(LocalDate day, int count) {
        return IntStream.range(0, count).mapToObj(i -> itinerary(day, i)).toList();
    }

    public static DaySnapshotWrite write(
            LocalDate day, UUID runId, List<AggregatedItinerary> itineraries, SortedSet<ProviderId> missing) {
        SortedSet<ProviderId> contributing = new TreeSet<>(ALL);
        contributing.removeAll(missing);
        return new DaySnapshotWrite(
                day, runId, itineraries, contributing, missing, Instant.parse("2026-11-05T12:00:00Z"));
    }

    public static DaySnapshotWrite complete(LocalDate day, UUID runId, List<AggregatedItinerary> itineraries) {
        return write(day, runId, itineraries, new TreeSet<>());
    }
}
