package com.andresps.gdsmock.inventory.domain.model;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;
import java.util.TreeMap;

/** The mock's fixed exchange rates, units per 1 EUR (EUR excluded: it is the base); spec 008. */
public record FxRates(Map<Currency, BigDecimal> unitsPerEur) {

    public FxRates {
        unitsPerEur = Map.copyOf(unitsPerEur);
    }

    /** Rates by ISO code, sorted. */
    public Map<String, BigDecimal> byCode() {
        Map<String, BigDecimal> byCode = new TreeMap<>();
        unitsPerEur.forEach((currency, rate) -> byCode.put(currency.getCurrencyCode(), rate));
        return byCode;
    }
}
