package com.andresps.gdsmock.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Spec 008 AC-12: the currency a provider prices in, at a fixed number of units per EUR. */
class FeedCurrencyTest {

    private static final FeedCurrency USD = new FeedCurrency(Currency.getInstance("USD"), new BigDecimal("1.25"));

    @ParameterizedTest
    @CsvSource({"129.90, 162.38", "100.00, 125.00", "0.10, 0.12", "0.30, 0.38", "-12.50, -15.62", "0.00, 0.00"})
    @DisplayName("AC-12: a generated EUR price is multiplied by the rate and rounded to 2 decimals HALF_EVEN")
    void converts(String eur, String usd) {
        assertThat(USD.fromEur(new BigDecimal(eur))).isEqualTo(new BigDecimal(usd));
        assertThat(USD.code()).isEqualTo("USD");
    }

    @Test
    @DisplayName("AC-12: EUR leaves the generated price untouched (same bytes as before)")
    void eurIsIdentity() {
        BigDecimal price = new BigDecimal("129.90");

        assertThat(FeedCurrency.EUR.fromEur(price)).isSameAs(price);
        assertThat(FeedCurrency.EUR.code()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("AC-12: the rate must be positive, and exactly 1 for EUR")
    void validatesRate() {
        assertThatThrownBy(() -> new FeedCurrency(Currency.getInstance("USD"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USD");
        assertThatThrownBy(() -> new FeedCurrency(Currency.getInstance("EUR"), new BigDecimal("1.1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
