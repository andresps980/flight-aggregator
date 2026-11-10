package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The Frankfurter {@code /latest} shape (spec 008 AC-15): {@code {"amount":1.0,"base":"EUR","date":"…","rates":{…}}};
 * a rate is units of that currency per 1 EUR.
 */
public record FxRatesResponse(double amount, String base, String date, Map<String, BigDecimal> rates) {}
