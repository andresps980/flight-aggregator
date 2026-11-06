package com.andresps.flights.search.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** A keyset position: the {@code (finalPrice, id)} of the last row of a page (ADR-0008). Price at scale 2. */
public record PagePosition(BigDecimal finalPrice, long id) {

    public PagePosition {
        Objects.requireNonNull(finalPrice, "finalPrice");
        if (finalPrice.signum() < 0 || id < 0) {
            throw new IllegalArgumentException("a page position is never negative");
        }
        finalPrice = finalPrice.setScale(2, RoundingMode.UNNECESSARY);
    }
}
