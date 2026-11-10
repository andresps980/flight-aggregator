package com.andresps.flights.aggregation.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Exchange rates of one ingestion run (spec 008, ADR-0025): units of each currency per 1 EUR, as the FX API publishes
 * them, so {@code EUR = amount / rate}. EUR is implicit (rate 1). Immutable.
 *
 * <p>{@link #unavailable()} is the run's value when the FX API failed: EUR still converts, any other currency throws
 * {@link ExchangeRatesUnavailableException}.
 */
public final class ExchangeRates {

    private static final Currency EUR = Money.EUR;
    private static final int SCALE = 2;
    private static final ExchangeRates UNAVAILABLE = new ExchangeRates(null, Map.of());

    private final LocalDate date;
    private final Map<Currency, BigDecimal> unitsPerEur;

    private ExchangeRates(LocalDate date, Map<Currency, BigDecimal> unitsPerEur) {
        this.date = date;
        this.unitsPerEur = unitsPerEur;
    }

    /**
     * @param date the date the rates were published for
     * @param unitsPerEur units of each currency per 1 EUR; each must be positive, and EUR, if present, exactly 1
     */
    public static ExchangeRates of(LocalDate date, Map<Currency, BigDecimal> unitsPerEur) {
        InvalidFlightDataException.requireField(date, "Rates date");
        InvalidFlightDataException.requireField(unitsPerEur, "Rates");
        unitsPerEur.forEach((currency, rate) -> {
            InvalidFlightDataException.requireField(currency, "Rate currency");
            if (rate == null || rate.signum() <= 0) {
                throw new InvalidFlightDataException("Rate for " + currency + " must be positive, got "
                        + (rate == null ? null : rate.toPlainString()));
            }
            if (EUR.equals(currency) && rate.compareTo(BigDecimal.ONE) != 0) {
                throw new InvalidFlightDataException("Rate for EUR must be 1 (the base), got " + rate.toPlainString());
            }
        });
        return new ExchangeRates(date, Map.copyOf(unitsPerEur));
    }

    /** No rates: the FX API failed. Converts EUR only. */
    public static ExchangeRates unavailable() {
        return UNAVAILABLE;
    }

    public boolean available() {
        return date != null;
    }

    /** The publication date of the rates; empty when unavailable. */
    public Optional<LocalDate> date() {
        return Optional.ofNullable(date);
    }

    /**
     * The EUR value of {@code amount} in {@code currency}: the exact quotient rounded once to 2 decimals
     * {@code HALF_EVEN}.
     *
     * @throws InvalidFlightDataException if the amount is out of bounds, or the rates don't include the currency
     * @throws ExchangeRatesUnavailableException if the currency is not EUR and there are no rates
     */
    public Money toEur(BigDecimal amount, Currency currency) {
        Money.requireBounded(amount);
        Objects.requireNonNull(currency, "currency");
        if (EUR.equals(currency)) {
            return Money.eur(amount);
        }
        if (!available()) {
            throw new ExchangeRatesUnavailableException("No exchange rates to convert " + currency.getCurrencyCode());
        }
        BigDecimal rate = unitsPerEur.get(currency);
        if (rate == null) {
            throw new InvalidFlightDataException("No exchange rate for " + currency.getCurrencyCode());
        }
        return Money.eur(amount.divide(rate, SCALE, RoundingMode.HALF_EVEN));
    }

    @Override
    public String toString() {
        return available() ? "ExchangeRates" + unitsPerEur + " of " + date : "ExchangeRates[unavailable]";
    }
}
