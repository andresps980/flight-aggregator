package com.andresps.flights.aggregation.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

    @ParameterizedTest
    @CsvSource({"7,7.00", "10.1,10.10", "99.904,99.90", "99.896,99.90", "0.125,0.12", "0.135,0.14", "0,0.00"})
    @DisplayName("AC-24/AC-8: amounts are normalised to scale 2 with HALF_EVEN")
    void normalisesToScaleTwoHalfEven(String raw, String expected) {
        Money money = Money.eur(raw);

        assertThat(money.amount()).isEqualTo(new BigDecimal(expected));
        assertThat(money.amount().scale()).isEqualTo(2);
        assertThat(money.currency()).isEqualTo(Currency.getInstance("EUR"));
    }

    @Test
    @DisplayName("AC-8: amounts equal after normalisation are equal money")
    void equalAfterNormalisation() {
        assertThat(Money.eur("99.904")).isEqualTo(Money.eur("99.896")).isEqualTo(Money.eur("99.9"));
    }

    @Test
    @DisplayName("AC-24: rejects negative amounts, even if they would round to zero")
    void rejectsNegative() {
        assertThatThrownBy(() -> Money.eur("-0.01")).isInstanceOf(InvalidFlightDataException.class);
        assertThatThrownBy(() -> Money.eur("-0.001")).isInstanceOf(InvalidFlightDataException.class);
    }

    @Test
    @DisplayName("AC-24: rejects an amount that is not a number")
    void rejectsNonNumeric() {
        assertThatThrownBy(() -> Money.eur("12,50")).isInstanceOf(InvalidFlightDataException.class);
        assertThatThrownBy(() -> Money.eur((String) null)).isInstanceOf(InvalidFlightDataException.class);
    }

    @Test
    @DisplayName("AC-24: only EUR is supported")
    void rejectsOtherCurrencies() {
        assertThatThrownBy(() -> new Money(BigDecimal.TEN, Currency.getInstance("USD")))
                .isInstanceOf(InvalidFlightDataException.class);
    }

    @Test
    @DisplayName("AC-24: rejects null amount or currency")
    void rejectsNulls() {
        assertThatThrownBy(() -> new Money(null, Currency.getInstance("EUR")))
                .isInstanceOf(InvalidFlightDataException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, null)).isInstanceOf(InvalidFlightDataException.class);
    }

    @Test
    @DisplayName("AC-4: money is ordered by amount")
    void ordersByAmount() {
        assertThat(Money.eur("99.90")).isLessThan(Money.eur("105.00"));
    }

    @Test
    @DisplayName("AC-24: zero is not positive")
    void positivity() {
        assertThat(Money.eur("0.00").isPositive()).isFalse();
        assertThat(Money.eur("0.01").isPositive()).isTrue();
    }
}
