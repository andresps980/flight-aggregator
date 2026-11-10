package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

import static com.andresps.flights.ingestion.infrastructure.adapter.out.provider.ParserTestSupport.stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.flights.aggregation.domain.model.ExchangeRates;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.gamma.GammaCsvParser;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AC-5: the adapter closes the body stream it receives, whatever the outcome of parsing it. */
class HttpProviderFeedBodyTest {

    private static final String HEADER = "REF,PRICE,CURRENCY,SEGMENTS\n";
    private static final String LINE =
            "GAM-1,100.00,EUR,IB0345~MAD~2026-11-06T09:00~Europe/Madrid~BCN~2026-11-06T10:25~Europe/Madrid\n";

    private final HttpProviderFeed feed = new HttpProviderFeed(
            new GammaCsvParser(),
            URI.create("http://localhost:1"),
            ResilienceSettings.defaults(),
            ResiliencePolicies.circuitBreaker(
                    "mock-gamma", ResilienceSettings.defaults().circuitBreaker()));

    @Test
    @DisplayName("AC-5: the body is closed after a successful parse")
    void closedOnSuccess() throws IOException {
        CloseRecorder body = new CloseRecorder(stream(HEADER + LINE));

        PartitionBuffer buffer = feed.read(body, ExchangeRates.unavailable());

        assertThat(buffer.offers()).hasSize(1);
        assertThat(body.closed).isTrue();
    }

    @Test
    @DisplayName("AC-5: the body is closed when records are malformed (the partition still succeeds)")
    void closedOnMalformedRecord() throws IOException {
        CloseRecorder body = new CloseRecorder(stream(HEADER + LINE.replace("~MAD~", "~M4D~") + LINE));

        PartitionBuffer buffer = feed.read(body, ExchangeRates.unavailable());

        assertThat(buffer.recordsReceived()).isEqualTo(2);
        assertThat(body.closed).isTrue();
    }

    @Test
    @DisplayName("AC-5: the body is closed when the document is broken")
    void closedOnBrokenDocument() {
        CloseRecorder body = new CloseRecorder(stream(HEADER + LINE.strip()));

        assertThatThrownBy(() -> feed.read(body, ExchangeRates.unavailable()))
                .isInstanceOf(MalformedDocumentException.class);
        assertThat(body.closed).isTrue();
    }

    @Test
    @DisplayName("AC-5: the body is closed when reading it fails")
    void closedOnIoError() {
        CloseRecorder body = new CloseRecorder(ParserTestSupport.failingAfter(HEADER + LINE));

        assertThatThrownBy(() -> feed.read(body, ExchangeRates.unavailable())).isInstanceOf(IOException.class);
        assertThat(body.closed).isTrue();
    }

    private static final class CloseRecorder extends FilterInputStream {

        private boolean closed;

        CloseRecorder(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
