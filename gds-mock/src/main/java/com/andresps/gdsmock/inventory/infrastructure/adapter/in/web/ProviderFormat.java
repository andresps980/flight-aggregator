package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web;

import com.andresps.gdsmock.inventory.domain.model.ProviderRecord;
import com.andresps.gdsmock.shared.domain.ProviderId;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import org.springframework.http.MediaType;

/**
 * A provider's legacy wire format. Adding a provider means adding one implementation (ADR-0012).
 *
 * <p>Contract: {@link #write} consumes records one at a time and writes them as it goes (never buffering the whole
 * feed), produces a valid document for an empty feed, does not close {@code out}, and reports any failure of the
 * output (e.g. a client that disconnected) as an {@link IOException}, after which it consumes no more records.
 */
public interface ProviderFormat {

    ProviderId providerId();

    MediaType mediaType();

    void write(Iterator<ProviderRecord> records, OutputStream out) throws IOException;
}
