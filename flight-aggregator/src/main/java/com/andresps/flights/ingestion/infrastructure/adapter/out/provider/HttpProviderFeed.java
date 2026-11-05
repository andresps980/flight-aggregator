package com.andresps.flights.ingestion.infrastructure.adapter.out.provider;

import com.andresps.flights.aggregation.domain.model.ProviderId;
import com.andresps.flights.ingestion.application.port.out.PartitionFetchException;
import com.andresps.flights.ingestion.application.port.out.ProviderFeed;
import com.andresps.flights.ingestion.domain.CircuitState;
import com.andresps.flights.ingestion.domain.FailureKind;
import com.andresps.flights.ingestion.domain.ProviderPartition;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * A provider feed over HTTP: {@code GET {base-url}/{provider}/flights?date={day}}, the body streamed into the provider's
 * {@link FeedParser} (ADR-0014, ADR-0015).
 *
 * <p>One <b>attempt</b> is the request plus reading and parsing the whole body into a fresh {@link PartitionBuffer}. From
 * the inside out: the attempt runs under the read timeout (first byte, gaps between bytes) and a total attempt
 * deadline; it goes through the provider's circuit breaker; and the breaker call is retried with exponential backoff
 * and jitter. Only a completed attempt's buffer becomes the partition.
 *
 * <p>Each attempt runs on its own virtual thread while the caller waits for it with the attempt deadline. Interrupting
 * a virtual thread blocked on a socket closes the socket, so both the attempt deadline and a cancelled caller (run
 * deadline) abort the request at once, whatever kind of thread called {@link #fetch}. {@code HttpURLConnection}
 * (Spring's {@link SimpleClientHttpRequestFactory}) is used because its read timeout also applies while the body is
 * read.
 */
public final class HttpProviderFeed implements ProviderFeed {

    private static final Logger LOG = LoggerFactory.getLogger(HttpProviderFeed.class);

    private final FeedParser parser;
    private final ResilienceSettings settings;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final RestClient client;

    public HttpProviderFeed(
            FeedParser parser, URI baseUrl, ResilienceSettings settings, CircuitBreaker circuitBreaker) {
        this.parser = Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(baseUrl, "baseUrl");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        this.retry = ResiliencePolicies.retry(parser.provider().value(), settings.retry());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(settings.connectTimeout());
        requestFactory.setReadTimeout(settings.readTimeout());
        this.client = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl.toString())
                .build();
    }

    @Override
    public ProviderId provider() {
        return parser.provider();
    }

    @Override
    public CircuitState circuitState() {
        return switch (circuitBreaker.getState()) {
            case OPEN, FORCED_OPEN -> CircuitState.OPEN;
            case HALF_OPEN -> CircuitState.HALF_OPEN;
            case CLOSED, DISABLED, METRICS_ONLY -> CircuitState.CLOSED;
        };
    }

    @Override
    public ProviderPartition fetch(LocalDate day) {
        AtomicInteger attempts = new AtomicInteger();
        try {
            PartitionBuffer buffer = retry.executeSupplier(() -> {
                if (Thread.currentThread().isInterrupted()) {
                    throw cancelled(null);
                }
                return circuitBreaker.executeSupplier(() -> {
                    attempts.incrementAndGet();
                    return attempt(day);
                });
            });
            return buffer.toPartition(provider(), day, attempts.get());
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                // Cancelled (run deadline). Interrupted during the backoff, Resilience4j re-sets the flag and rethrows
                // the previous attempt's failure, which must not be reported as that failure.
                throw new PartitionFetchException(FailureKind.RUN_TIMEOUT, attempts.get(), "fetch cancelled", e);
            }
            if (e instanceof AttemptFailedException failure) {
                throw new PartitionFetchException(failure.kind(), attempts.get(), failure.getMessage(), failure);
            }
            if (e instanceof CallNotPermittedException open) {
                throw new PartitionFetchException(
                        FailureKind.CIRCUIT_OPEN,
                        attempts.get(),
                        "circuit breaker of " + provider() + " is open",
                        open);
            }
            throw e;
        }
    }

    /** One request + whole body on a virtual worker thread, aborted if it outlives the attempt deadline. */
    private PartitionBuffer attempt(LocalDate day) {
        FutureTask<PartitionBuffer> exchange = new FutureTask<>(() -> exchange(day));
        Thread worker =
                Thread.ofVirtual().name("feed-" + provider() + "-" + day).start(exchange);
        try {
            return exchange.get(settings.attemptTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            abort(exchange, worker);
            if (Thread.currentThread().isInterrupted()) {
                throw cancelled(e); // cancelled while aborting: not the provider's fault
            }
            throw failed(
                    new AttemptFailedException(
                            FailureKind.TIMEOUT, "attempt deadline of " + settings.attemptTimeout() + " exceeded", e),
                    day);
        } catch (InterruptedException e) {
            abort(exchange, worker);
            Thread.currentThread().interrupt();
            throw cancelled(e);
        } catch (ExecutionException e) {
            throw failed(classify(e.getCause()), day);
        }
    }

    /**
     * Interrupts the worker (closing its socket) and waits for it for at most one read timeout, so a request outlives
     * its attempt by that much at worst.
     */
    private void abort(FutureTask<PartitionBuffer> exchange, Thread worker) {
        exchange.cancel(true);
        try {
            if (!worker.join(settings.readTimeout())) {
                LOG.warn("Aborted attempt for {} still running after {}", provider(), settings.readTimeout());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private PartitionBuffer exchange(LocalDate day) {
        return client.get()
                .uri("/{provider}/flights?date={day}", provider().value(), day)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (!status.is2xxSuccessful()) {
                        throw statusFailure(status);
                    }
                    return read(response.getBody());
                });
    }

    /** Parses the body into a fresh buffer and closes it, whatever the outcome (spec 003, AC-5). */
    PartitionBuffer read(InputStream body) throws IOException {
        try (body) {
            PartitionBuffer buffer = new PartitionBuffer(settings.maxRecordsPerPartition());
            parser.parse(body, buffer);
            return buffer;
        }
    }

    private AttemptFailedException failed(AttemptFailedException failure, LocalDate day) {
        LOG.debug("Attempt for {} {} failed: {} {}", provider(), day, failure.kind(), failure.getMessage());
        return failure;
    }

    private static AttemptFailedException cancelled(Throwable cause) {
        return new AttemptFailedException(FailureKind.RUN_TIMEOUT, "fetch cancelled", cause);
    }

    private static AttemptFailedException statusFailure(HttpStatusCode status) {
        FailureKind kind = status.is5xxServerError() ? FailureKind.SERVER_ERROR : FailureKind.CLIENT_ERROR;
        return new AttemptFailedException(kind, "HTTP " + status.value(), null);
    }

    private AttemptFailedException classify(Throwable e) {
        if (e instanceof AttemptFailedException failure) {
            return failure;
        }
        MalformedDocumentException malformed = cause(e, MalformedDocumentException.class);
        if (malformed != null) {
            return new AttemptFailedException(FailureKind.MALFORMED_DOCUMENT, malformed.getMessage(), e);
        }
        if (cause(e, SocketTimeoutException.class) != null) {
            return new AttemptFailedException(
                    FailureKind.TIMEOUT, "no data for " + settings.readTimeout() + " (read timeout)", e);
        }
        IOException io = cause(e, IOException.class);
        if (io != null) {
            return new AttemptFailedException(FailureKind.IO_ERROR, io.toString(), e);
        }
        if (e instanceof RuntimeException bug) {
            throw bug; // not a provider failure: reported as UNEXPECTED_ERROR by the use case
        }
        if (e instanceof Error error) {
            throw error; // e.g. OutOfMemoryError: never disguised as a provider failure
        }
        throw new IllegalStateException("Unexpected checked failure fetching " + provider(), e);
    }

    private static <T extends Throwable> T cause(Throwable e, Class<T> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }
}
