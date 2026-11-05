package com.andresps.flights.aggregation.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Non-negative EUR amount, always at scale 2. Inputs with more decimals are rounded {@code HALF_EVEN} (ADR-0010), so
 * amounts that differ only beyond the cent are equal.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public static final Currency EUR = Currency.getInstance("EUR");
    private static final int SCALE = 2;

    public Money {
        InvalidFlightDataException.requireField(amount, "Amount");
        InvalidFlightDataException.requireField(currency, "Currency");
        if (!EUR.equals(currency)) {
            throw new InvalidFlightDataException("Only EUR is supported, got " + currency);
        }
        if (amount.signum() < 0) {
            throw new InvalidFlightDataException("Amount must not be negative, got " + amount.toPlainString());
        }
        amount = amount.setScale(SCALE, RoundingMode.HALF_EVEN);
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
