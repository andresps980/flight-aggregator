package com.andresps.flights.search.domain;

import java.util.regex.Pattern;

/** Items per page: 1–100, 20 by default (spec 005 AC-6, AC-14). */
public record PageSize(int value) {

    public static final int DEFAULT = 20;
    public static final int MAX = 100;
    private static final Pattern DIGITS = Pattern.compile("\\d{1,3}");

    public PageSize {
        if (value < 1 || value > MAX) {
            throw invalid();
        }
    }

    /** {@code null} or blank means the default. */
    public static PageSize parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new PageSize(DEFAULT);
        }
        String value = raw.strip();
        if (!DIGITS.matcher(value).matches()) {
            throw invalid();
        }
        try {
            return new PageSize(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            throw invalid(); // unreachable after the 1-3 digit check; kept so the parse never escapes as a 500
        }
    }

    private static InvalidSearchRequestException invalid() {
        return new InvalidSearchRequestException("limit must be an integer between 1 and " + MAX);
    }
}
