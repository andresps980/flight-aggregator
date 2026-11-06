package com.andresps.flights.search;

import com.andresps.flights.aggregation.domain.deduplication.AggregatedItinerary;
import com.andresps.flights.aggregation.domain.model.AirportCode;
import com.andresps.flights.aggregation.domain.model.FlightNumber;
import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.Itinerary;
import com.andresps.flights.aggregation.domain.model.Money;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.model.Segment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeSet;

/**
 * gds-mock-shaped itineraries for volume tests (spec 005 AC-22/23): the mock's 34 airports and 24 airlines, routes
 * drawn uniformly (distinct airports), 1/2/3 segments at 50/35/15 %, base fares 30–430 EUR plus 60 EUR per extra
 * segment with the providers' ±25 % variation, 5 % markup. Deterministic per (day, index).
 */
public final class SearchVolume {

    static final List<String> AIRPORTS = List.of(
            "MAD", "BCN", "LHR", "LGW", "CDG", "ORY", "FRA", "MUC", "AMS", "FCO", "MXP", "LIS", "OPO", "DUB", "ZRH",
            "VIE", "CPH", "ATH", "JFK", "BOS", "MIA", "ORD", "LAX", "LIM", "BOG", "PTY", "EZE", "NBO", "JNB", "DXB",
            "DOH", "SIN", "HND", "SYD");
    static final List<String> AIRLINES = List.of(
            "IB", "VY", "UX", "BA", "AF", "KL", "LH", "AZ", "TP", "EI", "LX", "OS", "SK", "TK", "AA", "DL", "UA", "AM",
            "LA", "AV", "EK", "QR", "SQ", "NH");
    private static final List<ProviderId> PROVIDERS =
            List.of(new ProviderId("mock-alpha"), new ProviderId("mock-beta"), new ProviderId("mock-gamma"));
    private static final BigDecimal MARKUP = new BigDecimal("1.05");

    private SearchVolume() {}

    public static List<AggregatedItinerary> day(LocalDate day, int count) {
        List<AggregatedItinerary> itineraries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            itineraries.add(itinerary(day, i));
        }
        return itineraries;
    }

    /**
     * Unique within the day: the first flight number and the departure minute come from {@code index} (as in the
     * persistence fixtures), so keys never collide for up to 1440 × 9999 itineraries per day.
     */
    static AggregatedItinerary itinerary(LocalDate day, int index) {
        SplittableRandom random = new SplittableRandom(day.toEpochDay() * 10_000_019L + index);
        int roll = random.nextInt(100);
        int segmentCount = roll < 50 ? 1 : roll < 85 ? 2 : 3;
        List<String> pool = new ArrayList<>(AIRPORTS);
        List<String> airports = new ArrayList<>();
        for (int a = 0; a <= segmentCount; a++) {
            airports.add(pool.remove(random.nextInt(pool.size())));
        }
        String airline = AIRLINES.get(random.nextInt(AIRLINES.size()));
        Instant departure = day.atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofMinutes(index % 1440));
        List<Segment> segments = new ArrayList<>();
        Instant at = departure;
        for (int s = 0; s < segmentCount; s++) {
            int number = s == 0 ? index / 1440 + 1 : 5000 + s * 100 + random.nextInt(100);
            Instant arrival = at.plus(Duration.ofMinutes(45 + 5L * random.nextInt(136)));
            segments.add(new Segment(
                    FlightNumber.parse(airline + number),
                    new AirportCode(airports.get(s)),
                    new AirportCode(airports.get(s + 1)),
                    at,
                    arrival));
            at = arrival.plus(Duration.ofMinutes(60 + 5L * random.nextInt(61)));
        }
        BigDecimal fare = BigDecimal.valueOf(3_000L + random.nextInt(40_000) + 6_000L * (segmentCount - 1), 2)
                .multiply(BigDecimal.valueOf(0.90 + random.nextDouble() * 0.25))
                .setScale(2, RoundingMode.HALF_EVEN);
        ProviderId provider = PROVIDERS.get(random.nextInt(PROVIDERS.size()));
        FlightOffer offer = new FlightOffer(new Itinerary(segments), provider, Money.eur(fare));
        TreeSet<ProviderId> providers = new TreeSet<>(List.of(provider));
        if (random.nextInt(10) < 4) {
            providers.add(PROVIDERS.get(random.nextInt(PROVIDERS.size())));
        }
        return new AggregatedItinerary(offer.itinerary().key(), offer, Money.eur(fare.multiply(MARKUP)), providers);
    }
}
