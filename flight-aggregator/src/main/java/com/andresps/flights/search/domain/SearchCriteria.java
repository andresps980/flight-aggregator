package com.andresps.flights.search.domain;

import com.andresps.flights.aggregation.domain.model.AirlineCode;
import com.andresps.flights.aggregation.domain.model.AirportCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The normalized search filters (spec 005 AC-1, AC-2, AC-12 – AC-15). Every field is optional ({@code null} = no
 * filter); codes are upper case, {@code maxPrice} has scale 2, so equal filters are equal records with equal
 * {@link #canonical()} forms and fingerprints.
 */
public record SearchCriteria(
        AirportCode origin, AirportCode destination, LocalDate date, BigDecimal maxPrice, AirlineCode carrier) {

    /** Accepted {@code date} window around today (UTC): Q1 of spec 005. */
    public static final int DAYS_BEFORE_TODAY = 1;

    public static final int DAYS_AFTER_TODAY = 365;

    private static final Pattern AIRPORT = Pattern.compile("[A-Za-z]{3}");
    private static final Pattern AIRLINE = Pattern.compile("[A-Za-z0-9]{2}");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,9}(\\.\\d{1,2})?");
    private static final SearchCriteria NONE = new SearchCriteria(null, null, null, null, null);

    public static SearchCriteria none() {
        return NONE;
    }

    /**
     * Parses raw query parameters; {@code null} or blank means "no filter". {@code today} is the UTC date of the
     * request, for the date window.
     *
     * @throws InvalidSearchRequestException naming the first invalid parameter
     */
    public static SearchCriteria parse(
            String origin, String destination, String date, String maxPrice, String carrier, LocalDate today) {
        return new SearchCriteria(
                airport(origin, "origin"),
                airport(destination, "destination"),
                date(date, today),
                amount(maxPrice),
                airline(carrier));
    }

    /** One line naming every filter in a fixed order, absent ones empty: the input of fingerprints and cache keys. */
    public String canonical() {
        return "o=" + text(origin) + "|d=" + text(destination) + "|dt=" + text(date) + "|p="
                + (maxPrice == null ? "" : maxPrice.toPlainString()) + "|c=" + text(carrier);
    }

    /** The first 64 bits of SHA-256 over {@link #canonical()}, as 16 lower-case hex characters (ADR-0021). */
    public String fingerprint() {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(canonical().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    /** {@code LocalDate.parse} is strict: 2026-02-30 is not a date. */
    private static LocalDate isoDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static boolean absent(String raw) {
        return raw == null || raw.isBlank();
    }

    private static AirportCode airport(String raw, String parameter) {
        if (absent(raw)) {
            return null;
        }
        String value = raw.strip();
        if (!AIRPORT.matcher(value).matches()) {
            throw new InvalidSearchRequestException(parameter + " must be a 3-letter IATA airport code");
        }
        return new AirportCode(value.toUpperCase(Locale.ROOT));
    }

    private static AirlineCode airline(String raw) {
        if (absent(raw)) {
            return null;
        }
        String value = raw.strip();
        if (!AIRLINE.matcher(value).matches()) {
            throw new InvalidSearchRequestException("carrier must be a 2-character IATA airline code (A-Z, 0-9)");
        }
        return new AirlineCode(value.toUpperCase(Locale.ROOT));
    }

    private static BigDecimal amount(String raw) {
        if (absent(raw)) {
            return null;
        }
        String value = raw.strip();
        if (!AMOUNT.matcher(value).matches()) {
            throw new InvalidSearchRequestException(
                    "maxPrice must be a non-negative amount with at most 2 decimals, below 1000000000");
        }
        return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static LocalDate date(String raw, LocalDate today) {
        if (absent(raw)) {
            return null;
        }
        String value = raw.strip();
        LocalDate date = ISO_DATE.matcher(value).matches() ? isoDate(value) : null;
        if (date == null) {
            throw new InvalidSearchRequestException("date must be an ISO date (YYYY-MM-DD)");
        }
        LocalDate first = today.minusDays(DAYS_BEFORE_TODAY);
        LocalDate last = today.plusDays(DAYS_AFTER_TODAY);
        if (date.isBefore(first) || date.isAfter(last)) {
            throw new InvalidSearchRequestException("date must be between " + first + " and " + last);
        }
        return date;
    }
}
