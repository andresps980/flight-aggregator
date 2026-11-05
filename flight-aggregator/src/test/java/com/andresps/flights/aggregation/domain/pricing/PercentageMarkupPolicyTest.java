package com.andresps.flights.aggregation.domain.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.aggregation.domain.model.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PercentageMarkupPolicyTest {

    @Test
    @DisplayName("AC-9: the default policy adds 5% (100.00 -> 105.00)")
    void defaultPolicyAddsFivePercent() {
        MarkupPolicy policy = PercentageMarkupPolicy.defaultPolicy();

        assertThat(policy.apply(Money.eur("100.00"))).isEqualTo(Money.eur("105.00"));
        assertThat(PercentageMarkupPolicy.DEFAULT_PERCENTAGE).isEqualByComparingTo("5");
    }

    @ParameterizedTest(name = "{0}% of {1} -> {2}")
    @CsvSource({"10,100.00,110.00", "0,100.00,100.00", "12.5,80.00,90.00", "5,0.00,0.00"})
    @DisplayName("AC-10: the markup percentage is configurable")
    void appliesConfiguredPercentage(String percentage, String base, String expected) {
        MarkupPolicy policy = new PercentageMarkupPolicy(new BigDecimal(percentage));

        assertThat(policy.apply(Money.eur(base))).isEqualTo(Money.eur(expected));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"10.10,10.60", "10.30,10.82", "0.10,0.10", "0.01,0.01", "99.90,104.90"})
    @DisplayName("AC-11: the final price is rounded to scale 2 with HALF_EVEN")
    void roundsHalfEven(String base, String expected) {
        Money finalPrice = PercentageMarkupPolicy.defaultPolicy().apply(Money.eur(base));

        assertThat(finalPrice.amount()).isEqualTo(new BigDecimal(expected));
        assertThat(finalPrice.currency().getCurrencyCode()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("AC-12: a negative markup percentage is rejected")
    void rejectsNegativePercentage() {
        BigDecimal negative = new BigDecimal("-0.01");

        assertThatThrownBy(() -> new PercentageMarkupPolicy(negative)).isInstanceOf(InvalidMarkupException.class);
    }

    @Test
    @DisplayName("AC-12: a missing markup percentage is rejected")
    void rejectsNullPercentage() {
        assertThatThrownBy(() -> new PercentageMarkupPolicy(null)).isInstanceOf(InvalidMarkupException.class);
    }
}
