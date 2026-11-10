package com.andresps.flights.ingestion;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feeds and expected figures recorded from gds-mock ({@code deterministic} profile, seed 42, volume 1,400, reference
 * date 2026-11-05, 7 horizon days), served through WireMock. Recorded once with:
 *
 * <pre>
 * java -jar gds-mock/target/gds-mock-*.jar --spring.profiles.active=deterministic --server.port=18081 \
 *      --gds.world.volume=1400 --gds.world.reference-date=2026-11-05
 * curl "localhost:18081/{provider}/flights?date={day}"; curl "localhost:18081/admin/world/stats[?date={day}]"
 * </pre>
 *
 * Regenerate them if the mock's generator changes (data is deterministic per mock version).
 *
 * <p>The recording doesn't depend on the JVM's time-zone database: gds-mock only uses zones whose rules are stable
 * across tzdata versions ({@code CatalogTimeZonesTest} in gds-mock), so Beta's offsets and Gamma's local times convert
 * back to the same instants on any JDK 21.
 */
public final class RecordedGdsMock {

    public static final String ALPHA = "mock-alpha";
    public static final String BETA = "mock-beta";
    public static final String GAMMA = "mock-gamma";
    public static final List<String> PROVIDERS = List.of(ALPHA, BETA, GAMMA);

    /** Noon on the reference date: {@code ALREADY_DEPARTED} figures hold on that date (spec 002). */
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC);

    public static final List<LocalDate> DAYS = IntStream.rangeClosed(6, 12)
            .mapToObj(d -> LocalDate.of(2026, 11, d))
            .toList();

    private static final String ROOT = "/gds-mock/seed42-v1400-2026-11-05/";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RecordedGdsMock() {}

    public static byte[] feed(String provider, LocalDate day) {
        return resource(ROOT + provider + "/" + day + "." + extension(provider));
    }

    public static JsonNode stats() {
        return JSON.readTree(resource(ROOT + "stats.json"));
    }

    public static JsonNode stats(LocalDate day) {
        return JSON.readTree(resource(ROOT + "stats-" + day + ".json"));
    }

    public static String path(String provider) {
        return "/" + provider + "/flights";
    }

    public static MappingBuilder request(String provider, LocalDate day) {
        return get(urlPathEqualTo(path(provider))).withQueryParam("date", equalTo(day.toString()));
    }

    public static ResponseDefinitionBuilder ok(String provider, byte[] body) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", contentType(provider))
                .withBody(body);
    }

    /** Where the FX API double is served on the same WireMock (spec 008): {@code {base}/fx/latest?base=EUR}. */
    public static final String FX_BASE_PATH = "/fx";

    /** Spec 008: rates whose conversions of a doubled price are exact (2 units per EUR). */
    public static final String FX_BODY =
            "{\"amount\":1.0,\"base\":\"EUR\",\"date\":\"2026-11-04\",\"rates\":{\"GBP\":2,\"USD\":2}}";

    public static MappingBuilder exchangeRatesRequest() {
        return get(urlPathEqualTo(FX_BASE_PATH + "/latest")).withQueryParam("base", equalTo("EUR"));
    }

    public static void stubExchangeRates(WireMockServer server) {
        server.stubFor(exchangeRatesRequest()
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(FX_BODY)));
    }

    /** Stubs every recorded partition of every provider. */
    public static void stubAll(WireMockServer server) {
        PROVIDERS.forEach(provider -> stubProvider(server, provider));
    }

    public static void stubProvider(WireMockServer server, String provider) {
        DAYS.forEach(day -> server.stubFor(request(provider, day).willReturn(ok(provider, feed(provider, day)))));
    }

    private static String extension(String provider) {
        return switch (provider) {
            case ALPHA -> "json";
            case BETA -> "xml";
            case GAMMA -> "csv";
            default -> throw new IllegalArgumentException(provider);
        };
    }

    private static String contentType(String provider) {
        return switch (provider) {
            case ALPHA -> "application/json";
            case BETA -> "application/xml";
            case GAMMA -> "text/csv;charset=UTF-8";
            default -> throw new IllegalArgumentException(provider);
        };
    }

    private static byte[] resource(String path) {
        try (InputStream in = RecordedGdsMock.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
