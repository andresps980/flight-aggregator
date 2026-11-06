package com.andresps.flights.search;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes spec 004's read model directly with SQL, so search tests can shape exactly the rows they need (prices, ties,
 * departed flights, day freshness). {@link #publish()} bumps the data version as an ingestion transaction would.
 */
public final class SearchRows {

    private static final AtomicInteger FLIGHT = new AtomicInteger(1);
    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000005");

    private final JdbcClient jdbc;

    public SearchRows(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** A direct flight; adjust it with the {@code with*} methods. */
    public static Row row(String origin, String destination, String carrier, String departure, String finalPrice) {
        return new Row(origin, destination, carrier, Instant.parse(departure), new BigDecimal(finalPrice), 0, null);
    }

    /** Stores the row and returns its generated id. */
    public long insert(Row row) {
        return jdbc.sql("""
                        INSERT INTO itinerary (itinerary_key, departure_date, origin, destination, departure_at,
                            arrival_at, airline, stops, base_price, final_price, selected_provider, providers,
                            segments, run_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'mock-alpha', '{mock-alpha,mock-gamma}', ?::jsonb, ?, ?)
                        RETURNING id""")
                .params(
                        row.newKey(),
                        row.departureDate(),
                        row.origin(),
                        row.destination(),
                        Timestamp.from(row.departure()),
                        Timestamp.from(row.arrival()),
                        row.carrier(),
                        row.stops(),
                        row.basePrice(),
                        row.finalPrice(),
                        row.segmentsJson(),
                        RUN,
                        Timestamp.from(Instant.parse("2026-11-06T10:00:00Z")))
                .query(Long.class)
                .single();
    }

    public List<Long> insertAll(List<Row> rows) {
        List<Long> ids = new ArrayList<>();
        rows.forEach(r -> ids.add(insert(r)));
        return ids;
    }

    /** A complete, fresh day snapshot. */
    public void completeDay(LocalDate day) {
        day(day, true, List.of(), null);
    }

    public void incompleteDay(LocalDate day, String missingProvider) {
        day(day, false, List.of(missingProvider), null);
    }

    public void staleDay(LocalDate day, Instant staleSince) {
        day(day, true, List.of(), staleSince);
    }

    private void day(LocalDate day, boolean complete, List<String> missing, Instant staleSince) {
        jdbc.sql("""
                        INSERT INTO day_snapshot (departure_date, run_id, published_at, providers, missing_providers,
                            complete, itineraries, stale_since, last_run_id)
                        VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
                        ON CONFLICT (departure_date) DO UPDATE SET complete = EXCLUDED.complete,
                            missing_providers = EXCLUDED.missing_providers, stale_since = EXCLUDED.stale_since""")
                .params(
                        day,
                        RUN,
                        Timestamp.from(Instant.parse("2026-11-06T10:00:21Z")),
                        new String[] {"mock-alpha", "mock-gamma"},
                        missing.toArray(String[]::new),
                        complete,
                        staleSince == null ? null : Timestamp.from(staleSince),
                        RUN)
                .update();
    }

    /** As an ingestion transaction does after changing the read model (ADR-0022). */
    public void publish() {
        jdbc.sql("UPDATE data_version SET version = version + 1").update();
    }

    /** Changes a stored price without bumping the data version: only a cache hit can still show the old price. */
    public void changePriceBehindTheCache(long id, String finalPrice) {
        jdbc.sql("UPDATE itinerary SET final_price = ?, base_price = ? WHERE id = ?")
                .params(new BigDecimal(finalPrice), new BigDecimal(finalPrice), id)
                .update();
    }

    public record Row(
            String origin,
            String destination,
            String carrier,
            Instant departure,
            BigDecimal finalPrice,
            int stops,
            String fixedKey) {

        /** A readable itinerary key the test can find in responses. */
        public Row withKey(String value) {
            return new Row(origin, destination, carrier, departure, finalPrice, stops, value);
        }

        public Row withStops(int value) {
            return new Row(origin, destination, carrier, departure, finalPrice, value, fixedKey);
        }

        LocalDate departureDate() {
            return LocalDate.ofInstant(departure, ZoneOffset.UTC);
        }

        Instant arrival() {
            return departure.plus(Duration.ofHours(2L + 3L * stops));
        }

        BigDecimal basePrice() {
            return finalPrice.multiply(new BigDecimal("100")).divide(new BigDecimal("105"), 2, RoundingMode.HALF_EVEN);
        }

        /** A unique key per inserted row (flight numbers come from a counter). */
        String newKey() {
            return fixedKey != null ? fixedKey : segmentsKey();
        }

        private String segmentsKey() {
            return carrier + FLIGHT.getAndIncrement() + ":" + origin + "-" + destination + "@" + departure;
        }

        /** {@code stops} intermediate airports (ZRH, VIE, …) between origin and destination, 3 h apart. */
        String segmentsJson() {
            List<String> airports = new ArrayList<>();
            airports.add(origin);
            List<String> hubs = new ArrayList<>(List.of("ZRH", "VIE", "CPH", "OSL"));
            hubs.remove(origin);
            hubs.remove(destination);
            airports.addAll(hubs.subList(0, stops));
            airports.add(destination);
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < airports.size() - 1; i++) {
                Instant dep = departure.plus(Duration.ofHours(3L * i));
                json.append(i == 0 ? "" : ",")
                        .append("{\"flightNumber\":\"")
                        .append(carrier)
                        .append(100 + i)
                        .append("\",\"origin\":\"")
                        .append(airports.get(i))
                        .append("\",\"destination\":\"")
                        .append(airports.get(i + 1))
                        .append("\",\"departureAt\":\"")
                        .append(dep)
                        .append("\",\"arrivalAt\":\"")
                        .append(i == airports.size() - 2 ? arrival() : dep.plus(Duration.ofHours(2)))
                        .append("\"}");
            }
            return json.append("]").toString();
        }
    }
}
