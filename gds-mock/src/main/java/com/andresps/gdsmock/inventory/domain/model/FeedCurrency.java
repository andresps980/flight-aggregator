package com.andresps.gdsmock.inventory.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * A currency a provider may price in, with its fixed rate in units per 1 EUR (spec 008): the same rates gds-mock serves
 * on {@code /fx/latest}. A client converting back gets the world's EUR price only up to the two roundings: with a rate
 * below 1 (GBP 0.85) it can be a cent off, which is why the exact tests use a rate of 2.
 */
public record FeedCurrency(Currency currency, BigDecimal unitsPerEur) {

    private static final Currency EUR_CURRENCY = Currency.getInstance("EUR");
    public static final FeedCurrency EUR = new FeedCurrency(EUR_CURRENCY, BigDecimal.ONE);

    public FeedCurrency {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(unitsPerEur, "unitsPerEur");
        if (unitsPerEur.signum() <= 0) {
            throw new IllegalArgumentException(
                    "rate of " + currency.getCurrencyCode() + " must be positive, got " + unitsPerEur.toPlainString());
        }
        if (EUR_CURRENCY.equals(currency) && unitsPerEur.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("rate of EUR must be 1, got " + unitsPerEur.toPlainString());
        }
    }

    public String code() {
        return currency.getCurrencyCode();
    }

    /** The generated EUR price in this currency: × rate, 2 decimals {@code HALF_EVEN}; EUR returns it untouched. */
    public BigDecimal fromEur(BigDecimal eur) {
        if (EUR_CURRENCY.equals(currency)) {
            return eur;
        }
        return eur.multiply(unitsPerEur).setScale(2, RoundingMode.HALF_EVEN);
    }
}
