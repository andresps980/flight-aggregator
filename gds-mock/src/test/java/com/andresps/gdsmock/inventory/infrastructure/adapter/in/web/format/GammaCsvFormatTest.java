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

class GammaCsvFormatTest extends ProviderFormatContractTest {

    private final GammaCsvFormat format = new GammaCsvFormat();

    @Override
    ProviderFormat format() {
        return format;
    }

    @Override
    List<ParsedRecord> parse(byte[] body) {
        return ProviderPayloads.parseGamma(body);
    }

    @Override
    String prefix() {
        return ProviderPayloads.GAMMA_PREFIX;
    }

    @Test
    @DisplayName("AC-10: Mock-Gamma is served as text/csv under the id mock-gamma")
    void identity() {
        assertThat(format.providerId().value()).isEqualTo("mock-gamma");
        assertThat(format.mediaType().toString()).isEqualTo("text/csv;charset=UTF-8");
    }

    @Test
    @DisplayName("AC-10: header, then one line per itinerary; local times with IANA zones; zero-padded flights")
    void lines() {
        String[] lines = text(List.of(direct(), connection())).split("\n");

        assertThat(lines)
                .containsExactly(
                        "REF,PRICE_EUR,SEGMENTS",
                        "GAM-20261110-000017-0,129.90,IB0345~MAD~2026-11-10T09:00~Europe/Madrid~BCN~2026-11-10T10:25~Europe/Madrid",
                        "GAM-20261110-000018-0,450.00,"
                                + "BA0007~LHR~2026-11-10T10:00~Europe/London~JFK~2026-11-10T13:00~America/New_York"
                                + "|BA1203~JFK~2026-11-10T15:00~America/New_York~MIA~2026-11-10T18:10~America/New_York");
    }

    @Test
    @DisplayName("AC-10: an empty feed is only the header line")
    void emptyFeedIsHeader() {
        assertThat(text(List.of())).isEqualTo("REF,PRICE_EUR,SEGMENTS\n");
    }

    @Test
    @DisplayName("AC-7: an unparseable time uses a different, non-ISO layout")
    void unparseableTime() {
        assertThat(text(List.of(malformed(MalformationKind.UNPARSEABLE_TIME)))).contains("~10/11/2026 15h~");
    }

    private String text(List<com.andresps.gdsmock.inventory.domain.model.ProviderRecord> records) {
        return new String(write(records), StandardCharsets.UTF_8);
    }
}
