package com.andresps.flights.search.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The opaque keyset cursor of ADR-0021: base64url without padding of {@code v1|<finalPrice>|<id>|<fingerprint>}.
 * Unsigned: editing the position only moves the start of a search the client may run anyway.
 */
public record Cursor(PagePosition position, String fingerprint) {

    private static final String VERSION = "v1";
    private static final int MAX_TOKEN_LENGTH = 200;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern PRICE = Pattern.compile("\\d{1,10}(\\.\\d{1,2})?");
    private static final Pattern ID = Pattern.compile("\\d{1,19}");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{16}");

    public Cursor {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }

    public String encode() {
        String text = VERSION + "|" + position.finalPrice().toPlainString() + "|" + position.id() + "|" + fingerprint;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws InvalidSearchRequestException if the token is not a cursor this API issued (any malformation) */
    public static Cursor decode(String token) {
        if (token == null
                || token.length() > MAX_TOKEN_LENGTH
                || !TOKEN.matcher(token).matches()) {
            throw malformed();
        }
        String text;
        try {
            text = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw malformed();
        }
        String[] fields = text.split("\\|", -1);
        if (fields.length != 4
                || !VERSION.equals(fields[0])
                || !PRICE.matcher(fields[1]).matches()
                || !ID.matcher(fields[2]).matches()
                || !FINGERPRINT.matcher(fields[3]).matches()) {
            throw malformed();
        }
        try {
            return new Cursor(new PagePosition(new BigDecimal(fields[1]), Long.parseLong(fields[2])), fields[3]);
        } catch (NumberFormatException e) {
            throw malformed();
        }
    }

    /** @throws InvalidSearchRequestException if this cursor was issued for other filters (AC-16) */
    public void requireIssuedFor(String expectedFingerprint) {
        if (!fingerprint.equals(expectedFingerprint)) {
            throw new InvalidSearchRequestException(
                    "cursor was issued for different filters; start again without a cursor");
        }
    }

    private static InvalidSearchRequestException malformed() {
        return new InvalidSearchRequestException("cursor is malformed; use the nextCursor of a previous page");
    }
}
