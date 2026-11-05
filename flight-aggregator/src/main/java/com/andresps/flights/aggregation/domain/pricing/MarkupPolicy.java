package com.andresps.flights.aggregation.domain.pricing;

import com.andresps.flights.aggregation.domain.model.Money;

/**
 * How the OTA turns a provider's base price into the final selling price.
 *
 * <p>Contract (so policies are interchangeable): returns a non-null EUR amount at scale 2 that is never lower than the
 * base price, has no side effects, and returns the same result for the same input.
 */
@FunctionalInterface
public interface MarkupPolicy {

    Money apply(Money basePrice);
}
