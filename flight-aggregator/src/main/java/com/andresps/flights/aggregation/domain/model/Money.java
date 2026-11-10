package com.andresps.flights.aggregation.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Non-negative EUR amount, always at scale 2. Inputs with more decimals are rounded {@code HALF_EVEN} (ADR-0010), so
 * amounts that differ only beyond the cent are equal. Magnitudes are bounded so that malformed provider input such as
 * {@code 1E999999999} fails fast instead of triggering huge {@link BigDecimal} arithmetic.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public static final Currency EUR = Currency.getInstance("EUR");
    private static final int SCALE = 2;
    /** Bounds that keep arithmetic cheap on untrusted input: below 1,000,000,000 and at most 10 significant decimals. */
    private static final int MAX_INTEGER_DIGITS = 9;

    private static final int MAX_DECIMALS = 10;

    public Money {
        InvalidFlightDataException.requireField(amount, "Amount");
        InvalidFlightDataException.requireField(currency, "Currency");
        if (!EUR.equals(currency)) {
            throw new InvalidFlightDataException("Only EUR is supported, got " + currency);
        }
        if (amount.signum() < 0) {
            throw new InvalidFlightDataException("Amount must not be negative, got " + amount.toPlainString());
        }
        requireBounded(amount);
        amount = amount.setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Rejects magnitudes outside the bounds (at most {@value #MAX_INTEGER_DIGITS} integer digits and
     * {@value #MAX_DECIMALS} decimals), whatever the currency: also applied to a provider amount before it is converted
     * to EUR and to every exchange rate (spec 008 AC-8), so untrusted input never reaches expensive arithmetic.
     */
    public static void requireBounded(BigDecimal amount) {
        InvalidFlightDataException.requireField(amount, "Amount");
        BigDecimal significant = amount.stripTrailingZeros();
        if (significant.precision() - significant.scale() > MAX_INTEGER_DIGITS || significant.scale() > MAX_DECIMALS) {
            throw new InvalidFlightDataException("Amount out of range (max " + MAX_INTEGER_DIGITS + " integer digits, "
                    + MAX_DECIMALS + " decimals): " + amount.toString());
        }
    }

    public static Money eur(BigDecimal amount) {
        return new Money(amount, EUR);
    }

    public static Money eur(String amount) {
        InvalidFlightDataException.requireField(amount, "Amount");
        try {
            return eur(new BigDecimal(amount.strip()));
        } catch (NumberFormatException e) {
            throw new InvalidFlightDataException("Amount is not a number: '" + amount + "'");
        }
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
