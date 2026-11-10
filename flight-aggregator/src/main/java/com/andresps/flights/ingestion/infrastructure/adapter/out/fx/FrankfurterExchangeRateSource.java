package com.andresps.flights.ingestion.infrastructure.adapter.out.fx;

import com.andresps.flights.aggregation.domain.model.ExchangeRates;
import com.andresps.flights.aggregation.domain.model.ExchangeRatesUnavailableException;
import com.andresps.flights.aggregation.domain.model.InvalidFlightDataException;
import com.andresps.flights.ingestion.application.port.out.ExchangeRateSource;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.ResiliencePolicies;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.RetrySettings;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The latest ECB reference rates from the <a href="https://frankfurter.dev">Frankfurter</a> API, or any server with
 * its contract (gds-mock's {@code /fx/latest}): {@code GET {base-url}/latest?base=EUR} →
 * {@code {"amount":1.0,"base":"EUR","date":"2026-11-09","rates":{"USD":1.1193,...}}} (spec 008, ADR-0025).
 *
 * <p>Server errors, timeouts (read and per attempt) and I/O failures are retried; a 4xx or an invalid body (not JSON,
 * wrong base, a rate out of bounds, more than {@value #MAX_BODY_BYTES} bytes) is not. Every failure ends as
 * {@link ExchangeRatesUnavailableException}. Rate entries whose code is not ISO 4217 are skipped.
 */
public final class FrankfurterExchangeRateSource implements ExchangeRateSource {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern ISO_CODE = Pattern.compile("[A-Z]{3}");
    private static final String EUR = "EUR";

    /** A valid answer is under 1 KiB; anything far larger is not the FX API (and must not grow memory). */
    static final int MAX_BODY_BYTES = 64 * 1024;

    private final RestClient client;
    private final Duration attemptTimeout;
    private final Retry retry;

    /**
     * @param attemptTimeout total time per attempt, body included: the read timeout alone doesn't bound a server that
     *     sends a byte just before each timeout
     */
    public FrankfurterExchangeRateSource(
            URI baseUrl,
            Duration connectTimeout,
            Duration readTimeout,
            Duration attemptTimeout,
            RetrySettings retrySettings) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"));
        requestFactory.setReadTimeout(Objects.requireNonNull(readTimeout, "readTimeout"));
        this.attemptTimeout = Objects.requireNonNull(attemptTimeout, "attemptTimeout");
        this.client = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl.toString())
                .build();
        this.retry = Retry.of(
                "exchange-rates",
                RetryConfig.custom()
                        .maxAttempts(retrySettings.maxAttempts())
                        .intervalFunction(ResiliencePolicies.backoff(retrySettings))
                        .retryOnException(FrankfurterExchangeRateSource::retryable)
                        .failAfterMaxAttempts(false)
                        .build());
    }

    @Override
    public ExchangeRates latest() {
        String body;
        try {
            body = retry.executeSupplier(this::attempt);
        } catch (RetryableFailure | RestClientException e) {
            // the message names the status or the I/O failure, never the response body
            throw new ExchangeRatesUnavailableException("FX API request failed: " + describe(e), e);
        }
        return parse(body);
    }

    /** One request and its whole body on a virtual thread, abandoned (socket closed) after {@code attemptTimeout}. */
    private String attempt() {
        FutureTask<String> exchange = new FutureTask<>(this::request);
        Thread.ofVirtual().name("fx-rates").start(exchange);
        try {
            return exchange.get(attemptTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw new RetryableFailure("no complete answer within " + attemptTimeout, e);
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new ExchangeRatesUnavailableException("interrupted while reading the exchange rates", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw new RetryableFailure(String.valueOf(e.getCause()), e.getCause());
        }
    }

    private String request() {
        return client.get().uri("/latest?base={base}", EUR).exchange((request, response) -> {
            HttpStatusCode status = response.getStatusCode();
            if (status.is5xxServerError()) {
                throw new RetryableFailure("HTTP " + status.value(), null);
            }
            if (!status.is2xxSuccessful()) {
                throw new ExchangeRatesUnavailableException("FX API answered HTTP " + status.value());
            }
            try (InputStream in = response.getBody()) {
                byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES) {
                    throw invalid("larger than " + MAX_BODY_BYTES + " bytes");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        });
    }

    /** 5xx, an I/O failure or a timeout: worth another attempt. */
    private static boolean retryable(Throwable e) {
        return e instanceof RetryableFailure || e instanceof ResourceAccessException;
    }

    private static String describe(Exception e) {
        if (e instanceof ResourceAccessException access && access.getCause() != null) {
            return access.getCause().toString();
        }
        return e instanceof RetryableFailure ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** A failure of the FX API that a later attempt may not have: 5xx, timeout, I/O. */
    private static final class RetryableFailure extends RuntimeException {

        RetryableFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static ExchangeRates parse(String body) {
        try {
            JsonNode root = JSON.readTree(body == null ? "" : body);
            if (root == null || !root.isObject()) {
                throw invalid("not a JSON object");
            }
            if (!root.path("amount").isNumber()
                    || root.path("amount").decimalValue().compareTo(BigDecimal.ONE) != 0) {
                throw invalid("amount must be 1");
            }
            if (!EUR.equals(root.path("base").asString(null))) {
                throw invalid("base must be EUR, got " + root.path("base"));
            }
            LocalDate date = LocalDate.parse(root.path("date").asString(""));
            JsonNode rates = root.path("rates");
            if (!rates.isObject()) {
                throw invalid("rates must be an object");
            }
            Map<Currency, BigDecimal> unitsPerEur = new HashMap<>();
            for (Map.Entry<String, JsonNode> entry : rates.properties()) {
                if (!ISO_CODE.matcher(entry.getKey()).matches() || EUR.equals(entry.getKey())) {
                    continue; // spec 008 AC-11: EUR is the base and always converts at 1, whatever the body says
                }
                Currency currency;
                try {
                    currency = Currency.getInstance(entry.getKey());
                } catch (IllegalArgumentException e) {
                    continue; // spec 008 AC-11: not an ISO 4217 code
                }
                if (!entry.getValue().isNumber()) {
                    throw invalid("rate for " + entry.getKey() + " is not a number");
                }
                unitsPerEur.put(currency, entry.getValue().decimalValue());
            }
            return ExchangeRates.of(date, unitsPerEur);
        } catch (JacksonException | DateTimeException | InvalidFlightDataException e) {
            throw invalid(e.getMessage());
        }
    }

    private static ExchangeRatesUnavailableException invalid(String reason) {
        return new ExchangeRatesUnavailableException("FX API answered an invalid body: " + reason);
    }
}
