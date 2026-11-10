package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

import com.andresps.flights.aggregation.domain.model.AirlineCode;
import com.andresps.flights.aggregation.domain.model.AirportCode;
import com.andresps.flights.aggregation.domain.model.ExchangeRates;
import com.andresps.flights.aggregation.domain.model.ExchangeRatesUnavailableException;
import com.andresps.flights.aggregation.domain.model.FlightNumber;
import com.andresps.flights.aggregation.domain.model.FlightOffer;
import com.andresps.flights.aggregation.domain.model.InvalidFlightDataException;
import com.andresps.flights.aggregation.domain.model.Itinerary;
import com.andresps.flights.aggregation.domain.model.Money;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.aggregation.domain.model.Segment;
import com.andresps.flights.ingestion.domain.MalformedReason;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds spec 001 values from raw provider fields, classifying every failure (shared by the three parsers). A field
 * that is <b>missing</b> is an {@code INVALID_RECORD}; a field that is present but unusable gets its specific reason.
 */
public final class RecordValues {

    private static final Pattern ISO_CODE = Pattern.compile("[A-Z]{3}");

    private RecordValues() {}

    public static AirportCode airport(String raw) {
        String value = required(raw, "airport");
        try {
            return new AirportCode(value);
        } catch (InvalidFlightDataException e) {
            throw new MalformedRecordException(MalformedReason.BAD_AIRPORT_CODE, e.getMessage());
        }
    }

    /** A carrier-prefixed flight number, e.g. {@code IB345} or {@code IB0345}. */
    public static FlightNumber flightNumber(String raw) {
        String value = required(raw, "flight number");
        try {
            return FlightNumber.parse(value);
        } catch (InvalidFlightDataException e) {
            throw new MalformedRecordException(MalformedReason.BAD_FLIGHT_NUMBER, e.getMessage());
        }
    }

    /** Carrier and number given separately (Mock-Beta). */
    public static FlightNumber flightNumber(String carrier, String number) {
        String airline = required(carrier, "carrier");
        String digits = required(number, "flight number");
        try {
            return new FlightNumber(new AirlineCode(airline), digits.strip());
        } catch (InvalidFlightDataException e) {
            throw new MalformedRecordException(MalformedReason.BAD_FLIGHT_NUMBER, e.getMessage());
        }
    }

    /** A provider amount, in whatever currency: present, numeric and positive (spec 008: converted afterwards). */
    public static BigDecimal amount(String raw) {
        String value = required(raw, "price");
        try {
            return amount(new BigDecimal(value.strip()));
        } catch (NumberFormatException e) {
            throw invalid("price is not a number: '" + value + "'");
        }
    }

    public static BigDecimal amount(BigDecimal amount) {
        if (amount == null) {
            throw invalid("missing price");
        }
        if (amount.signum() <= 0) {
            throw new MalformedRecordException(
                    MalformedReason.NON_POSITIVE_PRICE, "price must be positive, got " + amount.toPlainString());
        }
        return amount;
    }

    /** An ISO 4217 code in upper case, e.g. {@code USD} (spec 008 AC-6). */
    public static Currency currency(String raw) {
        String value = required(raw, "currency").strip();
        if (!ISO_CODE.matcher(value).matches()) {
            throw invalid("currency must be an ISO 4217 code, got '" + raw + "'");
        }
        try {
            return Currency.getInstance(value);
        } catch (IllegalArgumentException e) {
            throw invalid("unknown currency '" + value + "'");
        }
    }

    /**
     * The amount in EUR with the run's rates (spec 008). A currency the rates don't include, or an amount out of
     * bounds, is an {@code INVALID_RECORD}; without rates, a non-EUR amount throws
     * {@link ExchangeRatesUnavailableException}, which fails the partition.
     */
    public static Money toEur(BigDecimal amount, Currency currency, ExchangeRates rates) {
        try {
            return rates.toEur(amount, currency);
        } catch (InvalidFlightDataException e) {
            throw invalid(e.getMessage());
        }
    }

    /** ISO-8601 date-time with offset, e.g. {@code 2026-11-10T09:00:00+01:00} (Mock-Beta). */
    public static Instant offsetDateTime(String raw) {
        String value = required(raw, "time");
        try {
            return OffsetDateTime.parse(value.strip()).toInstant();
        } catch (DateTimeException e) {
            throw unparseableTime(value);
        }
    }

    /** Local date-time without offset, e.g. {@code 2026-11-10T09:00} (Mock-Gamma, combined with a zone). */
    public static LocalDateTime localDateTime(String raw) {
        String value = required(raw, "time");
        try {
            return LocalDateTime.parse(value.strip());
        } catch (DateTimeException e) {
            throw unparseableTime(value);
        }
    }

    public static ZoneId zone(String raw) {
        String value = required(raw, "zone");
        try {
            return ZoneId.of(value.strip());
        } catch (DateTimeException e) {
            throw invalid("unknown zone '" + value + "'");
        }
    }

    public static MalformedRecordException unparseableTime(Object value) {
        return new MalformedRecordException(MalformedReason.UNPARSEABLE_TIME, "unparseable time '" + value + "'");
    }

    public static Segment segment(
            FlightNumber flight, AirportCode origin, AirportCode destination, Instant departure, Instant arrival) {
        try {
            return new Segment(flight, origin, destination, departure, arrival);
        } catch (InvalidFlightDataException e) {
            throw invalid(e.getMessage());
        }
    }

    public static FlightOffer offer(List<Segment> segments, ProviderId provider, Money price) {
        if (price == null) {
            throw invalid("missing price");
        }
        if (segments == null || segments.isEmpty()) {
            throw invalid("no segments");
        }
        try {
            return new FlightOffer(new Itinerary(segments), provider, price);
        } catch (InvalidFlightDataException e) {
            throw invalid(e.getMessage());
        }
    }

    public static String required(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw invalid("missing " + field);
        }
        return raw;
    }

    public static MalformedRecordException invalid(String message) {
        return new MalformedRecordException(MalformedReason.INVALID_RECORD, message);
    }
}
