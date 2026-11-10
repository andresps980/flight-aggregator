package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web;

import com.andresps.gdsmock.inventory.domain.generator.WorldGenerator;
import com.andresps.gdsmock.inventory.domain.model.FxRates;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /fx/latest[?base=EUR]}: the rates gds-mock prices with, in the shape of the Frankfurter API the aggregator
 * consumes (spec 008 AC-15), dated with the world's reference date. A test double for tests, CI and offline use; not
 * subject to chaos (the interceptor only covers {@code /{provider}/flights}).
 */
@RestController
public class FxRatesController {

    private static final String EUR = "EUR";

    private final FxRates rates;
    private final WorldGenerator generator;

    public FxRatesController(FxRates rates, WorldGenerator generator) {
        this.rates = rates;
        this.generator = generator;
    }

    @GetMapping(path = "/fx/latest", produces = MediaType.APPLICATION_JSON_VALUE)
    public FxRatesResponse latest(@RequestParam(defaultValue = EUR) String base) {
        if (!EUR.equals(base)) {
            throw new UnsupportedBaseCurrencyException(base);
        }
        return new FxRatesResponse(1.0, EUR, generator.horizon().referenceDate().toString(), rates.byCode());
    }
}
