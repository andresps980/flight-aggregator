package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.format;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.gdsmock.inventory.domain.model.MalformationKind;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.ProviderFormat;
import com.andresps.gdsmock.support.ParsedRecord;
import com.andresps.gdsmock.support.ProviderPayloads;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AlphaJsonFormatTest extends ProviderFormatContractTest {

    private final AlphaJsonFormat format = new AlphaJsonFormat();

    @Override
    ProviderFormat format() {
        return format;
    }

    @Override
    List<ParsedRecord> parse(byte[] body) {
        return ProviderPayloads.parseAlpha(body);
    }

    @Override
    String prefix() {
        return ProviderPayloads.ALPHA_PREFIX;
    }

    @Test
    @DisplayName("AC-8: Mock-Alpha is served as application/json under the id mock-alpha")
    void identity() {
        assertThat(format.providerId().value()).isEqualTo("mock-alpha");
        assertThat(format.mediaType()).isEqualTo(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("AC-8: a raw array of flat-ish objects with legacy names, epoch-millis times, prefixed flights")
    void legacyShape() {
        JsonNode root = JsonMapper.builder().build().readTree(write(List.of(direct())));

        assertThat(root.isArray()).isTrue();
        JsonNode offer = root.get(0);
        assertThat(offer.propertyNames()).containsExactly("offerRef", "cxr", "totFare", "cur", "legs");
        assertThat(offer.get("offerRef").asString()).isEqualTo("ALP-20261110-000017-0");
        assertThat(offer.get("cxr").asString()).isEqualTo("IB");
        assertThat(offer.get("cur").asString()).isEqualTo("EUR");
        JsonNode leg = offer.get("legs").get(0);
        assertThat(leg.propertyNames()).containsExactly("fltNum", "org", "dst", "depTs", "arrTs");
        assertThat(leg.get("fltNum").asString()).isEqualTo("IB345");
        assertThat(leg.get("depTs").isNumber()).isTrue();
        assertThat(leg.get("depTs").longValue()).isEqualTo(1_794_297_600_000L);
        assertThat(leg.get("arrTs").longValue()).isEqualTo(1_794_302_700_000L);
    }

    @Test
    @DisplayName("AC-8: prices keep two decimals as JSON numbers")
    void priceAsDecimalNumber() {
        String body = new String(write(List.of(direct())), StandardCharsets.UTF_8);

        assertThat(body).contains("\"totFare\":129.90");
    }

    @Test
    @DisplayName("AC-7: an unparseable time is the string \"TBD\" in a numeric field")
    void unparseableTime() {
        String body = new String(write(List.of(malformed(MalformationKind.UNPARSEABLE_TIME))), StandardCharsets.UTF_8);

        assertThat(body).contains("\"depTs\":\"TBD\"");
    }
}
