package com.andresps.flights.aggregation.domain.pricing;

import com.andresps.flights.aggregation.domain.model.Money;
import java.math.BigDecimal;

/**
 * Adds a percentage of the base price: {@code final = base × (100 + percentage) / 100}, computed exactly and rounded
 * once to scale 2 with {@code HALF_EVEN} by {@link Money}. 5% by default (ADR-0010).
 */
public record PercentageMarkupPolicy(BigDecimal percentage) implements MarkupPolicy {

    public static final BigDecimal DEFAULT_PERCENTAGE = new BigDecimal("5");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    public PercentageMarkupPolicy {
        if (percentage == null) {
            throw new InvalidMarkupException("Markup percentage is required");
        }
        if (percentage.signum() < 0) {
            throw new InvalidMarkupException(
                    "Markup percentage must not be negative, got " + percentage.toPlainString());
        }
    }

    public static PercentageMarkupPolicy defaultPolicy() {
        return new PercentageMarkupPolicy(DEFAULT_PERCENTAGE);
    }

    @Override
    public Money apply(Money basePrice) {
        BigDecimal factor = ONE_HUNDRED.add(percentage);
        return Money.eur(basePrice.amount().multiply(factor).movePointLeft(2));
    }
}
