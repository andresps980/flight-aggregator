package com.andresps.flights.ingestion.application.port.out;

import com.andresps.flights.aggregation.domain.model.ExchangeRates;
import com.andresps.flights.aggregation.domain.model.ExchangeRatesUnavailableException;

/**
 * The latest exchange rates, from EUR to every currency the source knows (spec 008, ADR-0025). Read once per ingestion
 * run.
 *
 * <p>Contract: returns rates that are available, or throws {@link ExchangeRatesUnavailableException} for any failure
 * (transport, status, invalid body), after its own retries; never anything else.
 */
@FunctionalInterface
public interface ExchangeRateSource {

    ExchangeRates latest();
}
