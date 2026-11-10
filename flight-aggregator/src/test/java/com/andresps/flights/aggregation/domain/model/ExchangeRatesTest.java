package com.andresps.flights.aggregation.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Spec 008: conversion of a provider amount to EUR with the run's rates (units of a currency per 1 EUR). */
class ExchangeRatesTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Currency XAF = Currency.getInstance("XAF");
    private static final LocalDate DATE = LocalDate.parse("2026-11-09");

    private static ExchangeRates rates(String usd, String gbp) {
        return ExchangeRates.of(DATE, Map.of(USD, new BigDecimal(usd), GBP, new BigDecimal(gbp)));
    }

    private static final ExchangeRates RATES = rates("1.25", "0.80");

    @Test
    @DisplayName("AC-1: 100.00 USD at 1.25 USD per EUR is 80.00 EUR")
    void convertsUsd() {
        assertThat(RATES.toEur(new BigDecimal("100.00"), USD)).isEqualTo(Money.eur("80.00"));
    }

    @Test
    @DisplayName("AC-1: 100.00 GBP at 0.80 GBP per EUR is 125.00 EUR")
    void convertsGbp() {
        assertThat(RATES.toEur(new BigDecimal("100.00"), GBP)).isEqualTo(Money.eur("125.00"));
    }

    @ParameterizedTest
    @CsvSource({
        "10.00, 1.6, 6.25",
        "1.00,  0.8, 1.25",
        "0.05,  2,   0.02", // 0.025: HALF_EVEN keeps the even cent
        "0.15,  2,   0.08", // 0.075 -> 0.08
        "100,   3,   33.33", // non-terminating quotient: rounded once
        "200,   3,   66.67"
    })
    @DisplayName("AC-4: the EUR amount is the exact quotient rounded once to 2 decimals HALF_EVEN")
    void roundsOnceHalfEven(String amount, String usdRate, String expected) {
        ExchangeRates rates = rates(usdRate, "1");

        Money eur = rates.toEur(new BigDecimal(amount), USD);

        assertThat(eur.amount()).isEqualTo(new BigDecimal(expected));
    }

    @Test
    @DisplayName("AC-3: EUR is used as is, with rates or without them")
    void eurNeedsNoRate() {
        assertThat(RATES.toEur(new BigDecimal("12.345"), EUR)).isEqualTo(Money.eur("12.34"));
        assertThat(ExchangeRates.unavailable().toEur(new BigDecimal("12.30"), EUR))
                .isEqualTo(Money.eur("12.30"));
    }

    @Test
    @DisplayName("AC-6: a currency the rates don't include is invalid data")
    void unknownCurrency() {
        assertThatThrownBy(() -> RATES.toEur(BigDecimal.TEN, XAF))
                .isInstanceOf(InvalidFlightDataException.class)
                .hasMessageContaining("XAF");
    }

    @Test
    @DisplayName("AC-10: without rates, any currency but EUR is unavailable, not invalid")
    void unavailableRates() {
        ExchangeRates none = ExchangeRates.unavailable();

        assertThat(none.available()).isFalse();
        assertThat(none.date()).isEmpty();
        assertThatThrownBy(() -> none.toEur(BigDecimal.TEN, USD))
                .isInstanceOf(ExchangeRatesUnavailableException.class)
                .isNotInstanceOf(InvalidFlightDataException.class)
                .hasMessageContaining("USD");
    }

    @Test
    @DisplayName("AC-11: EUR always converts at 1; a rate of exactly 1 for EUR is accepted")
    void eurIsImplicit() {
        ExchangeRates withEur = ExchangeRates.of(DATE, Map.of(EUR, BigDecimal.ONE, USD, new BigDecimal("2")));

        assertThat(withEur.toEur(new BigDecimal("10"), EUR)).isEqualTo(Money.eur("10.00"));
        assertThat(withEur.available()).isTrue();
        assertThat(withEur.date()).contains(DATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1.1"})
    @DisplayName("AC-10: a rate that is not positive is rejected")
    void rejectsNonPositiveRate(String rate) {
        assertThatThrownBy(() -> ExchangeRates.of(DATE, Map.of(USD, new BigDecimal(rate))))
                .isInstanceOf(InvalidFlightDataException.class)
                .hasMessageContaining("USD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1E+100000000", "1E-100000000", "1234567890", "0.00000000001"})
    @DisplayName("AC-10: a rate out of the Money bounds is rejected, so an untrusted rate never makes division costly")
    void rejectsRateOutOfBounds(String rate) {
        assertThatThrownBy(() -> ExchangeRates.of(DATE, Map.of(USD, new BigDecimal(rate))))
                .isInstanceOf(InvalidFlightDataException.class);
    }

    @Test
    @DisplayName("AC-10: a EUR rate other than 1 is rejected (the base is EUR)")
    void rejectsEurRateOtherThanOne() {
        assertThatThrownBy(() -> ExchangeRates.of(DATE, Map.of(EUR, new BigDecimal("1.1"))))
                .isInstanceOf(InvalidFlightDataException.class);
    }

    @Test
    @DisplayName("AC-8: the Money bounds apply to the provider amount before converting and to the result")
    void boundsBeforeAndAfter() {
        assertThatThrownBy(() -> RATES.toEur(new BigDecimal("1E999999999"), USD))
                .isInstanceOf(InvalidFlightDataException.class);
        assertThatThrownBy(() -> RATES.toEur(new BigDecimal("0.12345678901"), USD))
                .isInstanceOf(InvalidFlightDataException.class);
        // 999,999,999 GBP is within bounds, but 1,249,999,998.75 EUR is not
        assertThatThrownBy(() -> RATES.toEur(new BigDecimal("999999999"), GBP))
                .isInstanceOf(InvalidFlightDataException.class);
    }

    @Test
    @DisplayName("AC-9: rates are an immutable snapshot of the map they were built from")
    void immutableSnapshot() {
        Map<Currency, BigDecimal> source = new java.util.HashMap<>(Map.of(USD, new BigDecimal("2")));
        ExchangeRates rates = ExchangeRates.of(DATE, source);

        source.put(USD, new BigDecimal("4"));

        assertThat(rates.toEur(new BigDecimal("10"), USD)).isEqualTo(Money.eur("5.00"));
    }
}
