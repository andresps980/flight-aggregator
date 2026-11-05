package com.andresps.gdsmock.inventory.domain.generator;

import com.andresps.gdsmock.inventory.domain.model.Airport;
import com.andresps.gdsmock.inventory.domain.model.InvalidKind;
import com.andresps.gdsmock.inventory.domain.model.ScheduledSegment;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * Builds the segments of one itinerary: a valid schedule, or one that breaks exactly the given {@link InvalidKind} as
 * its first failing rule in spec 001's order (everything checked before it holds).
 *
 * <p>Times are whole multiples of 5 minutes. Valid connections are 1–6 h in 90 % of cases and 45 min–24 h otherwise.
 */
final class ScheduleFactory {

    private static final int STEP_MINUTES = 5;
    private static final int SLOTS_PER_DAY = 24 * 60 / STEP_MINUTES;

    /**
     * @param partitionDay the horizon day the itinerary belongs to
     * @param dayIndex position of {@code partitionDay} in the horizon (0-based)
     * @param horizonDays number of horizon days
     * @param referenceDate departure day of {@code ALREADY_DEPARTED} itineraries is the day before it
     * @param firstAirline airline of the first segment (unique per day together with {@code firstFlightNumber})
     */
    List<ScheduledSegment> build(
            SplittableRandom random,
            LocalDate partitionDay,
            int dayIndex,
            int horizonDays,
            LocalDate referenceDate,
            String firstAirline,
            int firstFlightNumber,
            Optional<InvalidKind> kind) {
        Instant start = departureWithin(random, partitionDay);
        if (kind.isEmpty()) {
            return chain(
                    random, start, firstAirline, firstFlightNumber, distinctAirports(random, segmentCount(random)));
        }
        return switch (kind.get()) {
            case MIXED_AIRLINES -> mixedAirlines(random, start, firstAirline, firstFlightNumber);
            case ARRIVAL_NOT_AFTER_DEPARTURE ->
                arrivalNotAfterDeparture(random, start, firstAirline, firstFlightNumber);
            case SEGMENTS_NOT_CHAINED -> notChained(random, start, firstAirline, firstFlightNumber);
            case REVISITED_AIRPORT -> revisited(random, start, firstAirline, firstFlightNumber);
            case CONNECTION_TOO_SHORT ->
                withConnection(
                        random,
                        start,
                        firstAirline,
                        firstFlightNumber,
                        minutes(-30 + STEP_MINUTES * random.nextInt(15)));
            case LAYOVER_TOO_LONG ->
                withConnection(
                        random,
                        start,
                        firstAirline,
                        firstFlightNumber,
                        minutes(1_445 + STEP_MINUTES * random.nextInt(288)));
            case ALREADY_DEPARTED ->
                chain(
                        random,
                        departedSlot(random, referenceDate.minusDays(1), dayIndex, horizonDays),
                        firstAirline,
                        firstFlightNumber,
                        distinctAirports(random, segmentCount(random)));
        };
    }

    private List<ScheduledSegment> mixedAirlines(SplittableRandom random, Instant start, String airline, int number) {
        List<ScheduledSegment> valid = chain(random, start, airline, number, distinctAirports(random, 2));
        int airlineIndex = Catalog.AIRLINES.indexOf(airline);
        String other = Catalog.AIRLINES.get(
                (airlineIndex + 1 + random.nextInt(Catalog.AIRLINES.size() - 1)) % Catalog.AIRLINES.size());
        ScheduledSegment second = valid.get(1);
        return List.of(
                valid.getFirst(),
                new ScheduledSegment(
                        other,
                        second.flightNumber(),
                        second.origin(),
                        second.destination(),
                        second.departure(),
                        second.arrival()));
    }

    private List<ScheduledSegment> arrivalNotAfterDeparture(
            SplittableRandom random, Instant start, String airline, int number) {
        List<Airport> airports = distinctAirports(random, 1);
        Instant arrival = start.minus(minutes(STEP_MINUTES * random.nextInt(25)));
        return List.of(new ScheduledSegment(airline, number, airports.get(0), airports.get(1), start, arrival));
    }

    private List<ScheduledSegment> notChained(SplittableRandom random, Instant start, String airline, int number) {
        List<Airport> airports = distinctAirports(random, 3);
        ScheduledSegment first = segment(random, airline, number, airports.get(0), airports.get(1), start);
        Instant next = first.arrival().plus(typicalConnection(random));
        ScheduledSegment second = segment(random, airline, nextNumber(random), airports.get(2), airports.get(3), next);
        return List.of(first, second);
    }

    private List<ScheduledSegment> revisited(SplittableRandom random, Instant start, String airline, int number) {
        List<Airport> airports = distinctAirports(random, 2);
        return chain(
                random,
                start,
                airline,
                number,
                List.of(airports.get(0), airports.get(1), airports.get(0), airports.get(2)));
    }

    private List<ScheduledSegment> withConnection(
            SplittableRandom random, Instant start, String airline, int number, Duration connection) {
        List<Airport> airports = distinctAirports(random, 2);
        ScheduledSegment first = segment(random, airline, number, airports.get(0), airports.get(1), start);
        ScheduledSegment second = segment(
                random,
                airline,
                nextNumber(random),
                airports.get(1),
                airports.get(2),
                first.arrival().plus(connection));
        return List.of(first, second);
    }

    /** Segments through {@code airports} in order, one airline, typical connections. */
    private List<ScheduledSegment> chain(
            SplittableRandom random, Instant start, String airline, int number, List<Airport> airports) {
        List<ScheduledSegment> segments = new ArrayList<>();
        Instant departure = start;
        for (int i = 0; i + 1 < airports.size(); i++) {
            int flightNumber = i == 0 ? number : nextNumber(random);
            ScheduledSegment segment =
                    segment(random, airline, flightNumber, airports.get(i), airports.get(i + 1), departure);
            segments.add(segment);
            departure = segment.arrival().plus(typicalConnection(random));
        }
        return List.copyOf(segments);
    }

    private static ScheduledSegment segment(
            SplittableRandom random,
            String airline,
            int number,
            Airport origin,
            Airport destination,
            Instant departure) {
        Duration flight = minutes(45 + STEP_MINUTES * random.nextInt(136));
        return new ScheduledSegment(airline, number, origin, destination, departure, departure.plus(flight));
    }

    private static int segmentCount(SplittableRandom random) {
        int roll = random.nextInt(100);
        return roll < 50 ? 1 : roll < 85 ? 2 : 3;
    }

    private static Duration typicalConnection(SplittableRandom random) {
        if (random.nextInt(10) < 9) {
            return minutes(60 + STEP_MINUTES * random.nextInt(61));
        }
        return minutes(45 + STEP_MINUTES * random.nextInt(280));
    }

    private static int nextNumber(SplittableRandom random) {
        return 1 + random.nextInt(Catalog.MAX_FLIGHT_NUMBER);
    }

    private static Instant departureWithin(SplittableRandom random, LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant().plus(minutes(STEP_MINUTES * random.nextInt(SLOTS_PER_DAY)));
    }

    /**
     * A departure on {@code day} whose 5-minute slot {@code k} satisfies {@code k mod horizonDays == dayIndex}.
     * Departed itineraries of every partition share one day; this keeps two of them with the same index (same first
     * airline and flight number) from also sharing a departure instant, so identities stay unique across the world.
     */
    private static Instant departedSlot(SplittableRandom random, LocalDate day, int dayIndex, int horizonDays) {
        int slot = horizonDays * random.nextInt(SLOTS_PER_DAY / horizonDays) + dayIndex;
        return day.atStartOfDay(ZoneOffset.UTC).toInstant().plus(minutes((long) STEP_MINUTES * slot));
    }

    /** {@code segments + 1} distinct airports (partial Fisher–Yates over the catalog). */
    private static List<Airport> distinctAirports(SplittableRandom random, int segments) {
        List<Airport> pool = new ArrayList<>(Catalog.AIRPORTS);
        List<Airport> picked = new ArrayList<>(segments + 1);
        for (int i = 0; i <= segments; i++) {
            picked.add(pool.remove(random.nextInt(pool.size())));
        }
        return picked;
    }

    private static Duration minutes(long minutes) {
        return Duration.ofMinutes(minutes);
    }
}
