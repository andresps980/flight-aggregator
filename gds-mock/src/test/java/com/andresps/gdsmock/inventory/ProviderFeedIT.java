package com.andresps.gdsmock.inventory;

import static com.andresps.gdsmock.support.WorldFixtures.ALPHA;
import static com.andresps.gdsmock.support.WorldFixtures.BETA;
import static com.andresps.gdsmock.support.WorldFixtures.GAMMA;
import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.gdsmock.inventory.domain.generator.WorldGenerator;
import com.andresps.gdsmock.shared.domain.ProviderId;
import com.andresps.gdsmock.support.Http;
import com.andresps.gdsmock.support.ParsedRecord;
import com.andresps.gdsmock.support.ProviderPayloads;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"deterministic", "it"})
class ProviderFeedIT {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private WorldGenerator generator;

    private Http http;

    @BeforeEach
    void client() {
        http = new Http(port);
    }

    record Provider(ProviderId id, String prefix, MediaType mediaType, Function<byte[], List<ParsedRecord>> parser) {
        @Override
        public String toString() {
            return id.value();
        }
    }

    static Stream<Arguments> providers() {
        return Stream.of(
                Arguments.of(new Provider(
                        ALPHA,
                        ProviderPayloads.ALPHA_PREFIX,
                        MediaType.APPLICATION_JSON,
                        ProviderPayloads::parseAlpha)),
                Arguments.of(new Provider(
                        BETA, ProviderPayloads.BETA_PREFIX, MediaType.APPLICATION_XML, ProviderPayloads::parseBeta)),
                Arguments.of(new Provider(
                        GAMMA,
                        ProviderPayloads.GAMMA_PREFIX,
                        MediaType.parseMediaType("text/csv;charset=UTF-8"),
                        ProviderPayloads::parseGamma)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    @DisplayName("AC-8, AC-9, AC-10, AC-11: each provider serves its records of the world in its own format")
    void servesItsRecords(Provider provider) {
        ResponseEntity<byte[]> response = http.get("/" + provider.id().value() + "/flights");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(provider.mediaType());
        var expected = generator
                .records(provider.id())
                .map(r -> ProviderPayloads.expected(r, provider.prefix()))
                .toList();
        assertThat(provider.parser().apply(response.getBody()))
                .hasSizeGreaterThan(500)
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    @DisplayName("AC-1: two calls return byte-identical responses")
    void deterministicResponses(Provider provider) {
        String path = "/" + provider.id().value() + "/flights";

        assertThat(http.get(path).getBody()).isEqualTo(http.get(path).getBody());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    @DisplayName("AC-12: responses are streamed (chunked, no Content-Length)")
    void streamed(Provider provider) {
        HttpHeaders headers = http.get("/" + provider.id().value() + "/flights").getHeaders();

        assertThat(headers.getFirst(HttpHeaders.TRANSFER_ENCODING)).isEqualTo("chunked");
        assertThat(headers.containsHeader(HttpHeaders.CONTENT_LENGTH)).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    @DisplayName("AC-13: ?date= partitions are disjoint and their union is the unfiltered response")
    void datePartitions(Provider provider) {
        String path = "/" + provider.id().value() + "/flights";
        List<ParsedRecord> union = new ArrayList<>();

        for (LocalDate day = LocalDate.of(2026, 11, 6);
                !day.isAfter(LocalDate.of(2026, 11, 12));
                day = day.plusDays(1)) {
            ResponseEntity<byte[]> partition = http.get(path + "?date=" + day);
            assertThat(partition.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<ParsedRecord> records = provider.parser().apply(partition.getBody());
            String dayPrefix = day.toString().replace("-", "");
            assertThat(records).allSatisfy(r -> assertThat(r.itineraryRef()).startsWith(dayPrefix));
            union.addAll(records);
        }

        assertThat(union).isEqualTo(provider.parser().apply(http.get(path).getBody()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-05", "2026-11-13", "2026-13-01", "tomorrow"})
    @DisplayName("AC-14 (error): a date outside the horizon or malformed is a 400 Problem Detail")
    void badDate(String date) {
        ResponseEntity<String> response = http.getText("/mock-beta/flights?date=" + date);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).contains("\"status\":400");
    }

    @Test
    @DisplayName("AC-14 (error): an unknown provider is a 404 Problem Detail")
    void unknownProvider() {
        ResponseEntity<String> response = http.getText("/mock-delta/flights");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).contains("mock-delta");
    }
}
