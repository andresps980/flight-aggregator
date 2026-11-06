package com.andresps.flights.search.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** ADR-0021 format: base64url (no padding) of {@code v1|<finalPrice>|<id>|<fingerprint>}. */
class CursorTest {

    private static final String FINGERPRINT = "7f3a9c12e4b05d68";

    private static String token(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("AC-7: a cursor round-trips its position and fingerprint")
    void roundTrips() {
        Cursor cursor = new Cursor(new PagePosition(new BigDecimal("433.00"), 82134L), FINGERPRINT);

        Cursor decoded = Cursor.decode(cursor.encode());

        assertThat(decoded).isEqualTo(cursor);
        assertThat(decoded.position().finalPrice()).isEqualByComparingTo("433.00");
        assertThat(decoded.position().id()).isEqualTo(82134L);
    }

    @Test
    @DisplayName("ADR-0021: the token is URL-safe base64 without padding of the documented layout")
    void hasTheDocumentedLayout() {
        String encoded = new Cursor(new PagePosition(new BigDecimal("433"), 82134L), FINGERPRINT).encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8))
                .isEqualTo("v1|433.00|82134|" + FINGERPRINT);
    }

    @Test
    @DisplayName("ADR-0021: a price with fewer decimals is normalized to scale 2, so equal positions encode equally")
    void normalizesThePrice() {
        assertThat(new PagePosition(new BigDecimal("12.5"), 1L))
                .isEqualTo(new PagePosition(new BigDecimal("12.50"), 1L));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "not base64 !",
                "%%%",
                "djF8NDMzLjAwfDgyMTM0fDdmM2E5YzEy===", // padding is not part of the format
            })
    @DisplayName("AC-16: a token that is not base64url is rejected")
    void rejectsNonBase64(String token) {
        assertThatThrownBy(() -> Cursor.decode(token))
                .isInstanceOf(InvalidSearchRequestException.class)
                .hasMessageContaining("cursor");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "", // empty payload
                "v2|433.00|82134|7f3a9c12e4b05d68", // unknown version
                "v1|433.00|82134", // missing field
                "v1|433.00|82134|7f3a9c12e4b05d68|extra", // extra field
                "v1|abc|82134|7f3a9c12e4b05d68", // price not a number
                "v1|-1.00|82134|7f3a9c12e4b05d68", // negative price
                "v1|433.001|82134|7f3a9c12e4b05d68", // more than 2 decimals
                "v1|1e3|82134|7f3a9c12e4b05d68", // exponent
                "v1|433.00|x|7f3a9c12e4b05d68", // id not a number
                "v1|433.00|-5|7f3a9c12e4b05d68", // negative id
                "v1|433.00|99999999999999999999|7f3a9c12e4b05d68", // id beyond long
                "v1|433.00|82134|7F3A9C12E4B05D68", // fingerprint not lower-case hex
                "v1|433.00|82134|7f3a9c12", // fingerprint too short
                "v1|433.00|82134|zzzzzzzzzzzzzzzz", // fingerprint not hex
            })
    @DisplayName("AC-16: a tampered or malformed payload is rejected")
    void rejectsMalformedPayload(String payload) {
        assertThatThrownBy(() -> Cursor.decode(token(payload)))
                .isInstanceOf(InvalidSearchRequestException.class)
                .hasMessageContaining("cursor");
    }

    @Test
    @DisplayName("AC-16: an oversized token is rejected before decoding")
    void rejectsOversizedToken() {
        assertThatThrownBy(() -> Cursor.decode("A".repeat(10_000)))
                .isInstanceOf(InvalidSearchRequestException.class)
                .hasMessageContaining("cursor");
    }

    @Test
    @DisplayName("AC-16: the fingerprint check names the cause")
    void checksTheFingerprint() {
        Cursor cursor = new Cursor(new PagePosition(new BigDecimal("1.00"), 1L), FINGERPRINT);

        cursor.requireIssuedFor(FINGERPRINT);
        assertThatThrownBy(() -> cursor.requireIssuedFor("0000000000000000"))
                .isInstanceOf(InvalidSearchRequestException.class)
                .hasMessageContaining("different filters");
    }
}
