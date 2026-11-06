package com.andresps.flights.search;

import com.andresps.flights.search.domain.Freshness;
import com.andresps.flights.search.domain.ItineraryView;
import com.andresps.flights.search.domain.SegmentView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/** Search domain values for unit tests. */
public final class SearchFixtures {

    public static final Instant PUBLISHED = Instant.parse("2026-11-06T10:00:00Z");

    private SearchFixtures() {}

    /** A direct MAD → JFK flight by IB, complete day, with the given key, departure and final price. */
    public static ItineraryView view(String key, String departure, String finalPrice) {
        Instant dep = Instant.parse(departure);
        Instant arr = dep.plusSeconds(8 * 3600);
        BigDecimal price = new BigDecimal(finalPrice).setScale(2, RoundingMode.UNNECESSARY);
        return new ItineraryView(
                key,
                "MAD",
                "JFK",
                LocalDate.ofInstant(dep, ZoneOffset.UTC),
                dep,
                arr,
                "IB",
                0,
                List.of(new SegmentView("IB6251", "MAD", "JFK", dep, arr)),
                price.multiply(new BigDecimal("100")).divide(new BigDecimal("105"), 2, RoundingMode.HALF_EVEN),
                price,
                "mock-alpha",
                List.of("mock-alpha", "mock-gamma"),
                Freshness.of(true, PUBLISHED, List.of(), null));
    }
}
