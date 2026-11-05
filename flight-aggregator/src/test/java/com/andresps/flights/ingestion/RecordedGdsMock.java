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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.zone.ZoneRulesProvider;
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
 * <p><b>Time-zone rules matter.</b> Mock-Beta writes offsets and Mock-Gamma local times computed with the recording
 * JVM's tzdata; the aggregator converts them back with the test JVM's. If a zone's rules changed in between (tzdata
 * 2026 moved {@code Africa/Casablanca} from UTC+1 to UTC+0), some instants shift and reconciliation fails. The tzdata
 * version used to record is in {@code tzdata-version.txt}; a test JVM with older rules fails fast with an explanation.
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

    static {
        requireTimeZoneRulesAtLeast(new String(resource(ROOT + "tzdata-version.txt"), StandardCharsets.UTF_8).strip());
    }

    private RecordedGdsMock() {}

    private static void requireTimeZoneRulesAtLeast(String recorded) {
        String jvm = ZoneRulesProvider.getVersions("Africa/Casablanca").lastKey();
        if (jvm.compareTo(recorded) < 0) {
            throw new IllegalStateException("The recorded gds-mock feeds use tzdata " + recorded + " but this JVM has "
                    + jvm + ": local times would convert to different instants (e.g. Africa/Casablanca). Use an "
                    + "up-to-date JDK 21 (Temurin 21.0.13+ ships 2026e) or re-record the fixtures.");
        }
    }

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
