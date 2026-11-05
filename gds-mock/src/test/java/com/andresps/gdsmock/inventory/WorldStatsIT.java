package com.andresps.gdsmock.inventory;

import static com.andresps.gdsmock.support.WorldFixtures.AGGREGATION_NOW;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.gdsmock.support.Http;
import com.andresps.gdsmock.support.ParsedRecord;
import com.andresps.gdsmock.support.ProviderPayloads;
import com.andresps.gdsmock.support.Spec001Rules;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC-20: every figure of {@code GET /admin/world/stats} equals what a client counts by parsing the three provider
 * responses and applying spec 001's rules (re-implemented in {@link Spec001Rules}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"deterministic", "it"})
class WorldStatsIT {

    private static final Map<String, Function<byte[], List<ParsedRecord>>> PARSERS = new LinkedHashMap<>();

    static {
        PARSERS.put("mock-alpha", ProviderPayloads::parseAlpha);
        PARSERS.put("mock-beta", ProviderPayloads::parseBeta);
        PARSERS.put("mock-gamma", ProviderPayloads::parseGamma);
    }

    @Value("${local.server.port}")
    private int port;

    private Http http;

    @BeforeEach
    void client() {
        http = new Http(port);
    }

    @ParameterizedTest(name = "query \"{0}\"")
    @ValueSource(strings = {"", "?date=2026-11-08"})
    @DisplayName("AC-20: stats equal the counts parsed from the three provider responses (whole horizon and one day)")
    void statsMatchParsedResponses(String query) {
        JsonNode stats = JsonMapper.builder()
                .build()
                .readTree(http.get("/admin/world/stats" + query).getBody());
        Map<String, List<ParsedRecord>> responses = new LinkedHashMap<>();
        PARSERS.forEach((provider, parser) -> responses.put(
                provider,
                parser.apply(http.get("/" + provider + "/flights" + query).getBody())));

        Map<String, Set<String>> providersByItinerary = new HashMap<>();
        Map<String, Long> discarded = new HashMap<>();
        Map<String, Set<String>> invalidItinerariesSeen = new HashMap<>();
        Set<String> producedIdentities = new HashSet<>();
        long validWellFormed = 0;

        for (var entry : responses.entrySet()) {
            String provider = entry.getKey();
            List<ParsedRecord> records = entry.getValue();
            JsonNode providerStats = stats.get("providers").get(provider);

            assertThat(providerStats.get("records").longValue()).isEqualTo(records.size());
            assertThat(providerStats.get("sameProviderDuplicates").longValue())
                    .isEqualTo(records.stream()
                            .filter(ParsedRecord::sameProviderDuplicate)
                            .count());
            JsonNode malformed = providerStats.get("malformed");
            assertThat(malformed.get("BAD_AIRPORT_CODE").longValue())
                    .isEqualTo(records.stream()
                            .filter(ParsedRecord::hasBadAirportCode)
                            .count());
            assertThat(malformed.get("NON_POSITIVE_PRICE").longValue())
                    .isEqualTo(records.stream()
                            .filter(ParsedRecord::hasNonPositivePrice)
                            .count());
            assertThat(malformed.get("UNPARSEABLE_TIME").longValue())
                    .isEqualTo(records.stream()
                            .filter(ParsedRecord::hasUnparseableTime)
                            .count());

            List<ParsedRecord> wellFormed =
                    records.stream().filter(r -> !r.malformed()).toList();
            assertThat(stats.get("expectedAggregation")
                            .get("offersReceived")
                            .get(provider)
                            .longValue())
                    .isEqualTo(wellFormed.size());

            for (ParsedRecord record : records) {
                providersByItinerary
                        .computeIfAbsent(record.itineraryRef(), k -> new HashSet<>())
                        .add(provider);
            }
            for (ParsedRecord record : wellFormed) {
                Optional<String> failure = Spec001Rules.firstFailure(record.segments(), AGGREGATION_NOW);
                if (failure.isPresent()) {
                    discarded.merge(failure.get(), 1L, Long::sum);
                    invalidItinerariesSeen
                            .computeIfAbsent(failure.get(), k -> new HashSet<>())
                            .add(record.itineraryRef());
                } else {
                    validWellFormed++;
                    producedIdentities.add(record.identity());
                }
            }
        }

        assertThat(stats.get("distinctItineraries").longValue()).isEqualTo(providersByItinerary.size());
        assertThat(stats.get("crossProviderDuplicates").longValue())
                .isEqualTo(providersByItinerary.values().stream()
                        .mapToLong(p -> p.size() - 1)
                        .sum());

        JsonNode expected = stats.get("expectedAggregation");
        for (String kind : List.of(
                "MIXED_AIRLINES",
                "ARRIVAL_NOT_AFTER_DEPARTURE",
                "SEGMENTS_NOT_CHAINED",
                "REVISITED_AIRPORT",
                "CONNECTION_TOO_SHORT",
                "LAYOVER_TOO_LONG",
                "ALREADY_DEPARTED")) {
            assertThat(expected.get("discarded").get(kind).longValue())
                    .as("discarded %s", kind)
                    .isEqualTo(discarded.getOrDefault(kind, 0L));
            // Itineraries whose every record is malformed can't be classified by a client, hence the lower bound.
            assertThat(stats.get("invalidItineraries").get(kind).longValue())
                    .as("invalid itineraries %s", kind)
                    .isGreaterThanOrEqualTo(
                            invalidItinerariesSeen.getOrDefault(kind, Set.of()).size());
        }
        assertThat(expected.get("itinerariesProduced").longValue()).isEqualTo(producedIdentities.size());
        assertThat(expected.get("duplicatesMerged").longValue()).isEqualTo(validWellFormed - producedIdentities.size());
    }

    @Test
    @DisplayName("AC-20: stats describe the reference date, horizon and requested day")
    void describesTheWorld() {
        JsonNode whole = JsonMapper.builder()
                .build()
                .readTree(http.get("/admin/world/stats").getBody());
        JsonNode day = JsonMapper.builder()
                .build()
                .readTree(http.get("/admin/world/stats?date=2026-11-08").getBody());

        assertThat(whole.get("referenceDate").asString()).isEqualTo("2026-11-05");
        assertThat(whole.get("horizon").get("from").asString()).isEqualTo("2026-11-06");
        assertThat(whole.get("horizon").get("to").asString()).isEqualTo("2026-11-12");
        assertThat(whole.get("date").isNull()).isTrue();
        assertThat(whole.get("distinctItineraries").longValue()).isEqualTo(2_000);
        assertThat(day.get("date").asString()).isEqualTo("2026-11-08");
        assertThat(day.get("distinctItineraries").longValue()).isBetween(285L, 286L);
    }

    @Test
    @DisplayName("AC-14, AC-20 (error): a stats date outside the horizon is a 400 Problem Detail")
    void badDate() {
        var response = http.getText("/admin/world/stats?date=2026-12-01");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }
}
