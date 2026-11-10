package com.andresps.flights.ingestion.infrastructure.adapter.out.fx;

import com.andresps.flights.aggregation.domain.model.ExchangeRates;
import com.andresps.flights.aggregation.domain.model.ExchangeRatesUnavailableException;
import com.andresps.flights.aggregation.domain.model.InvalidFlightDataException;
import com.andresps.flights.ingestion.application.port.out.ExchangeRateSource;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.ResiliencePolicies;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.RetrySettings;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.math.BigDecimal;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpServerErrorException;
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
 * <p>Server errors, timeouts and I/O failures are retried; a 4xx or an invalid body is not. Every failure ends as
 * {@link ExchangeRatesUnavailableException}. Rate entries whose code is not ISO 4217 are skipped.
 */
public final class FrankfurterExchangeRateSource implements ExchangeRateSource {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern ISO_CODE = Pattern.compile("[A-Z]{3}");
    private static final String EUR = "EUR";

    private final RestClient client;
    private final Retry retry;

    public FrankfurterExchangeRateSource(
            URI baseUrl, Duration connectTimeout, Duration readTimeout, RetrySettings retrySettings) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"));
        requestFactory.setReadTimeout(Objects.requireNonNull(readTimeout, "readTimeout"));
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
            body = retry.executeSupplier(() ->
                    client.get().uri("/latest?base={base}", EUR).retrieve().body(String.class));
        } catch (RestClientException e) {
            throw new ExchangeRatesUnavailableException("FX API request failed: " + e.getMessage(), e);
        }
        return parse(body);
    }

    private static boolean retryable(Throwable e) {
        return e instanceof HttpServerErrorException || e instanceof ResourceAccessException;
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
                if (!ISO_CODE.matcher(entry.getKey()).matches()) {
                    continue;
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
