package com.andresps.flights.ingestion.infrastructure.config;

import com.andresps.flights.ingestion.application.service.IngestionSettings;
import com.andresps.flights.ingestion.infrastructure.adapter.in.scheduling.ScheduleSettings;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.CircuitBreakerSettings;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.ResilienceSettings;
import com.andresps.flights.ingestion.infrastructure.adapter.out.provider.RetrySettings;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;

/**
 * {@code aggregator.*} settings (spec 003, docs/usage.md). Everything is validated when bound, so an invalid value stops
 * the startup with a message naming the full property (AC-23).
 *
 * <pre>
 * aggregator.providers.&lt;id&gt;.base-url          where the provider is served
 * aggregator.providers.&lt;id&gt;.resilience.*      per-provider overrides of aggregator.resilience.default.*
 * aggregator.ingestion.*                        horizon, markup, concurrency, run deadline
 * aggregator.resilience.default.*               timeouts, retry and circuit breaker of every provider
 * aggregator.ingestion.schedule.*               scheduled runs (spec 004)
 * aggregator.persistence.*                      max stale age of a day snapshot, run history (spec 004)
 * aggregator.exchange-rates.base-url            the FX API (spec 008): Frankfurter, or gds-mock's /fx double
 * </pre>
 */
@ConfigurationProperties("aggregator")
public record AggregatorProperties(
        Map<String, ProviderProperties> providers,
        @DefaultValue IngestionProperties ingestion,
        @DefaultValue ResilienceProperties resilience,
        @DefaultValue PersistenceProperties persistence,
        @DefaultValue ExchangeRatesProperties exchangeRates) {

    public AggregatorProperties {
        Map<String, ProviderProperties> copy = new LinkedHashMap<>(providers == null ? Map.of() : providers);
        providers = Collections.unmodifiableMap(copy);
        copy.forEach((id, provider) -> {
            if (provider == null
                    || provider.baseUrl() == null
                    || provider.baseUrl().toString().isBlank()) {
                throw new IllegalArgumentException("aggregator.providers." + id + ".base-url is required");
            }
            URI url = provider.baseUrl();
            if (!url.isAbsolute() || !List.of("http", "https").contains(url.getScheme()) || url.getHost() == null) {
                throw new IllegalArgumentException(
                        "aggregator.providers." + id + ".base-url must be an absolute http(s) URL, got " + url);
            }
        });
        // fail fast on invalid values: resolve everything once
        ingestion.validate();
        persistence.validate();
        exchangeRates.validate();
        ResilienceProperties defaults = resilience;
        copy.forEach((id, provider) -> resolve(defaults, id, provider));
    }

    /** The resilience of one provider: code defaults, then {@code aggregator.resilience.default}, then its overrides. */
    public ResilienceSettings resilienceFor(String providerId) {
        return resolve(resilience, providerId, providers.get(providerId));
    }

    public IngestionSettings ingestionSettings() {
        return ingestion.settings();
    }

    private static ResilienceSettings resolve(ResilienceProperties resilience, String id, ProviderProperties provider) {
        ResilienceSettings defaults = named(
                "aggregator.resilience.default.", () -> resilience.defaults().over(ResilienceSettings.defaults()));
        if (provider == null || provider.resilience() == null) {
            return defaults;
        }
        return named(
                "aggregator.providers." + id + ".resilience.",
                () -> provider.resilience().over(defaults));
    }

    /** Prefixes a validation message (which starts with the relative property name) with the full property path. */
    private static <T> T named(String prefix, Supplier<T> build) {
        try {
            return build.get();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(prefix + e.getMessage(), e);
        }
    }

    public record ProviderProperties(URI baseUrl, ResilienceOverride resilience) {}

    public record IngestionProperties(
            @DefaultValue("7") int horizonDays,
            @DefaultValue("5") BigDecimal markupPercentage,
            @DefaultValue("2") int maxConcurrentDays,
            @DefaultValue("2") int maxConcurrentPartitions,
            @DefaultValue("10m") Duration runTimeout,
            @DefaultValue ScheduleSettings schedule) {

        // Validated by the enclosing record: the binder may build intermediate instances of nested records.
        void validate() {
            if (markupPercentage == null || markupPercentage.signum() < 0) {
                throw new IllegalArgumentException(
                        "aggregator.ingestion.markup-percentage must not be negative, got " + markupPercentage);
            }
            settings();
        }

        IngestionSettings settings() {
            return named(
                    "aggregator.ingestion.",
                    () -> new IngestionSettings(horizonDays, maxConcurrentDays, maxConcurrentPartitions, runTimeout));
        }
    }

    /** Spec 004: how long a stale day snapshot is kept against partial data (ADR-0017), and how long runs are kept. */
    public record PersistenceProperties(
            @DefaultValue("2h") Duration maxStaleAge,
            @DefaultValue("7d") Duration runRetention) {

        void validate() {
            if (maxStaleAge == null || maxStaleAge.isNegative() || maxStaleAge.isZero()) {
                throw new IllegalArgumentException(
                        "aggregator.persistence.max-stale-age must be positive, got " + maxStaleAge);
            }
            if (runRetention == null || runRetention.isNegative() || runRetention.isZero()) {
                throw new IllegalArgumentException(
                        "aggregator.persistence.run-retention must be positive, got " + runRetention);
            }
        }
    }

    /** Spec 008: where the live exchange rates come from (a Frankfurter-compatible API, ADR-0025). */
    public record ExchangeRatesProperties(
            @DefaultValue("https://api.frankfurter.dev/v1") URI baseUrl) {

        void validate() {
            if (baseUrl == null
                    || !baseUrl.isAbsolute()
                    || !List.of("http", "https").contains(baseUrl.getScheme())
                    || baseUrl.getHost() == null) {
                throw new IllegalArgumentException(
                        "aggregator.exchange-rates.base-url must be an absolute http(s) URL, got " + baseUrl);
            }
        }
    }

    public record ResilienceProperties(
            @Name("default") @DefaultValue ResilienceOverride defaults) {}

    /** Any subset of the resilience settings; unset values are inherited. */
    public record ResilienceOverride(
            Duration connectTimeout,
            Duration readTimeout,
            Duration attemptTimeout,
            RetryOverride retry,
            CircuitBreakerOverride circuitBreaker,
            Long maxRecordsPerPartition) {

        ResilienceSettings over(ResilienceSettings base) {
            return new ResilienceSettings(
                    or(connectTimeout, base.connectTimeout()),
                    or(readTimeout, base.readTimeout()),
                    or(attemptTimeout, base.attemptTimeout()),
                    retry == null ? base.retry() : retry.over(base.retry()),
                    circuitBreaker == null ? base.circuitBreaker() : circuitBreaker.over(base.circuitBreaker()),
                    or(maxRecordsPerPartition, base.maxRecordsPerPartition()));
        }
    }

    public record RetryOverride(Integer maxAttempts, Duration initialBackoff, Double multiplier, Double jitter) {

        RetrySettings over(RetrySettings base) {
            return new RetrySettings(
                    or(maxAttempts, base.maxAttempts()),
                    or(initialBackoff, base.initialBackoff()),
                    or(multiplier, base.multiplier()),
                    or(jitter, base.jitter()));
        }
    }

    public record CircuitBreakerOverride(
            Integer slidingWindow,
            Integer minimumCalls,
            Float failureRateThreshold,
            Duration openWait,
            Integer halfOpenCalls) {

        CircuitBreakerSettings over(CircuitBreakerSettings base) {
            return new CircuitBreakerSettings(
                    or(slidingWindow, base.slidingWindow()),
                    or(minimumCalls, base.minimumCalls()),
                    or(failureRateThreshold, base.failureRateThreshold()),
                    or(openWait, base.openWait()),
                    or(halfOpenCalls, base.halfOpenCalls()));
        }
    }

    private static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }
}
