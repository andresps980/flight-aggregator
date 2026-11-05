package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Helpers shared by the parser tests. */
public final class ParserTestSupport {

    private ParserTestSupport() {}

    public static PartitionBuffer parse(FeedParser parser, String body) {
        PartitionBuffer buffer = new PartitionBuffer();
        try {
            parser.parse(stream(body), buffer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer;
    }

    public static InputStream stream(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Serves {@code head} and then fails with an {@link IOException}, like a connection dropped mid-body. Used to show
     * that records are delivered to the buffer while the body is being read (AC-5), not after reading it whole.
     */
    public static InputStream failingAfter(String head) {
        byte[] bytes = head.getBytes(StandardCharsets.UTF_8);
        return new InputStream() {
            private int position;

            @Override
            public int read() throws IOException {
                if (position >= bytes.length) {
                    throw new IOException("connection reset (test)");
                }
                return bytes[position++] & 0xFF;
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                if (position >= bytes.length) {
                    throw new IOException("connection reset (test)");
                }
                int count = Math.min(length, bytes.length - position);
                System.arraycopy(bytes, position, target, offset, count);
                position += count;
                return count;
            }
        };
    }

    public static Instant utc(String isoInstant) {
        return Instant.parse(isoInstant);
    }
}
