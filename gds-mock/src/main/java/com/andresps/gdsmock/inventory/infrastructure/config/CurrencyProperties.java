package com.andresps.gdsmock.inventory.infrastructure.config;

import com.andresps.gdsmock.inventory.domain.generator.ProviderCurrencies;
import com.andresps.gdsmock.inventory.domain.model.FeedCurrency;
import com.andresps.gdsmock.shared.domain.ProviderId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spec 008: {@code gds.providers.<id>.currencies} (ISO 4217 codes, default {@code [EUR]}) and {@code gds.fx.rates.<code>}
 * (units per 1 EUR). Validated when the generator is built: messages name the property.
 */
@ConfigurationProperties("gds")
public record CurrencyProperties(Map<String, ProviderProperties> providers, FxProperties fx) {

    private static final Pattern ISO_CODE = Pattern.compile("[A-Z]{3}");
    private static final String EUR = "EUR";

    public CurrencyProperties {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        fx = fx == null ? new FxProperties(Map.of()) : fx;
    }

    public record ProviderProperties(List<String> currencies) {}

    public record FxProperties(Map<String, BigDecimal> rates) {

        public FxProperties {
            rates = rates == null ? Map.of() : Map.copyOf(rates);
        }
    }

    /** The configured rates by currency, EUR excluded (it is the base); sorted by code. */
    public Map<Currency, BigDecimal> rates() {
        Map<Currency, BigDecimal> rates = new TreeMap<>(Comparator.comparing(Currency::getCurrencyCode));
        Set<String> codes = new HashSet<>();
        fx.rates().forEach((key, rate) -> {
            String code = key.toUpperCase(Locale.ROOT);
            String property = "gds.fx.rates." + code;
            if (!codes.add(code)) {
                throw new IllegalArgumentException(property + " is set twice (keys differ only by case)");
            }
            Currency currency = iso(code, property);
            if (rate == null || rate.signum() <= 0) {
                throw new IllegalArgumentException(property + " must be positive, got " + rate);
            }
            if (EUR.equals(code)) {
                if (rate.compareTo(BigDecimal.ONE) != 0) {
                    throw new IllegalArgumentException(property + " must be 1 (EUR is the base), got " + rate);
                }
            } else {
                rates.put(currency, rate);
            }
        });
        return rates;
    }

    /**
     * @param known the providers gds-mock serves
     * @throws IllegalArgumentException naming the property, for an unknown provider, an empty list, a code that is not
     *     ISO 4217 or a currency without a rate
     */
    public ProviderCurrencies providerCurrencies(Collection<ProviderId> known) {
        Map<Currency, BigDecimal> rates = rates();
        Map<ProviderId, List<FeedCurrency>> byProvider = new LinkedHashMap<>();
        providers.forEach((id, provider) -> {
            String property = "gds.providers." + id + ".currencies";
            ProviderId providerId = new ProviderId(id);
            if (!known.contains(providerId)) {
                throw new IllegalArgumentException(
                        "gds.providers.%s: unknown provider (known: %s)".formatted(id, known));
            }
            List<String> codes = provider == null || provider.currencies() == null ? List.of() : provider.currencies();
            if (codes.isEmpty() || codes.stream().allMatch(code -> code == null || code.isBlank())) {
                throw new IllegalArgumentException(property + " must list at least one currency");
            }
            List<FeedCurrency> currencies = new ArrayList<>();
            for (String raw : codes) {
                String code = raw == null ? "" : raw.strip();
                Currency currency = iso(code, property);
                if (EUR.equals(code)) {
                    currencies.add(FeedCurrency.EUR);
                } else if (rates.containsKey(currency)) {
                    currencies.add(new FeedCurrency(currency, rates.get(currency)));
                } else {
                    throw new IllegalArgumentException(
                            "%s: %s has no rate (set gds.fx.rates.%s)".formatted(property, code, code));
                }
            }
            byProvider.put(providerId, currencies);
        });
        return ProviderCurrencies.of(byProvider);
    }

    private static Currency iso(String code, String property) {
        if (!ISO_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException(property + ": '" + code + "' is not an ISO 4217 code");
        }
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(property + ": '" + code + "' is not an ISO 4217 code", e);
        }
    }
}
