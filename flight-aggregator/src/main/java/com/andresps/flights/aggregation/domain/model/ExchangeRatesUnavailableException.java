package com.andresps.flights.aggregation.domain.model;

/**
 * A price in a currency other than EUR could not be converted because the run has no exchange rates (the FX API
 * failed, spec 008 AC-10). Not invalid data: the record is fine, the rates are missing, so it fails the whole partition
 * instead of counting the record as malformed.
 */
public class ExchangeRatesUnavailableException extends RuntimeException {

    public ExchangeRatesUnavailableException(String message) {
        super(message);
    }

    public ExchangeRatesUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
