package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

import com.andresps.flights.aggregation.domain.model.ExchangeRates;
import com.andresps.flights.aggregation.domain.model.ExchangeRatesUnavailableException;
import com.andresps.flights.aggregation.domain.model.ProviderId;
import java.io.IOException;
import java.io.InputStream;

/**
 * Streaming parser of one provider's feed format. A new provider is a new parser (plus configuration); nothing else
 * changes.
 *
 * <p>Contract: reads the body record by record, adding each record to the buffer as an offer or as a malformed record
 * (one {@code MalformedReason}, the first failing field) and never holding the document in memory. A malformed record
 * never stops the parse. Every price is converted to EUR with the given rates, in the currency the record declares
 * (spec 008). The body is not closed (the caller owns it).
 */
public interface FeedParser {

    ProviderId provider();

    /**
     * @throws MalformedDocumentException if the document is structurally broken or truncated
     * @throws ExchangeRatesUnavailableException if a price is not in EUR and the rates are unavailable
     * @throws IOException if reading the body fails
     */
    void parse(InputStream body, ExchangeRates rates, PartitionBuffer buffer) throws IOException;
}
