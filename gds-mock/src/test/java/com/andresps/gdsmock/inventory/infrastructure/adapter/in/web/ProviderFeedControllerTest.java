package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web;

import static com.andresps.gdsmock.support.WorldFixtures.PROVIDERS;
import static com.andresps.gdsmock.support.WorldFixtures.generator;
import static com.andresps.gdsmock.support.WorldFixtures.settings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.andresps.gdsmock.inventory.domain.generator.WorldGenerator;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.format.AlphaJsonFormat;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.format.BetaXmlFormat;
import com.andresps.gdsmock.inventory.infrastructure.adapter.in.web.format.GammaCsvFormat;
import com.andresps.gdsmock.shared.domain.UnknownProviderException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;

@ExtendWith(OutputCaptureExtension.class)
class ProviderFeedControllerTest {

    private final WorldGenerator generator = generator(settings(2_000));
    private final ProviderFeedController controller = new ProviderFeedController(
            new ProviderFormats(List.of(new AlphaJsonFormat(), new BetaXmlFormat(), new GammaCsvFormat())),
            generator,
            new FeedRequestResolver(new LinkedHashSet<>(PROVIDERS), generator::horizon));

    @Test
    @DisplayName("AC-8, AC-12: the feed is a streaming body with the provider's content type")
    void streamingBody() throws IOException {
        var response = controller.flights("mock-alpha", "2026-11-06");
        var out = new ByteArrayOutputStream();

        response.getBody().writeTo(out);

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(out.toString()).startsWith("[{\"offerRef\":\"ALP-20261106-");
    }

    @Test
    @DisplayName("AC-12: a client disconnect ends the stream quietly, without rethrowing or logging an error")
    void clientDisconnectIsQuiet(CapturedOutput output) {
        var response = controller.flights("mock-gamma", null);
        OutputStream disconnecting = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Broken pipe");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("Broken pipe");
            }
        };

        assertThatCode(() -> response.getBody().writeTo(disconnecting)).doesNotThrowAnyException();
        assertThat(output.getAll()).doesNotContain("ERROR").doesNotContain("WARN");
    }

    @Test
    @DisplayName("AC-12: an output failure that is not a disconnect is logged as a warning (truncated feed)")
    void otherOutputFailureIsWarned(CapturedOutput output) {
        var response = controller.flights("mock-beta", null);
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("No space left on device");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("No space left on device");
            }
        };

        assertThatCode(() -> response.getBody().writeTo(failing)).doesNotThrowAnyException();
        assertThat(output.getAll()).contains("WARN").contains("mock-beta feed failed");
    }

    @Test
    @DisplayName("AC-14 (error): a date outside the horizon is rejected before any streaming starts")
    void dateCheckedOnRequestThread() {
        assertThatThrownBy(() -> controller.flights("mock-alpha", "2026-11-05"))
                .isInstanceOf(com.andresps.gdsmock.inventory.domain.generator.DateOutsideHorizonException.class);
    }

    @Test
    @DisplayName("AC-14 (error): an unknown provider is rejected before any streaming starts")
    void unknownProvider() {
        assertThatThrownBy(() -> controller.flights("mock-delta", null)).isInstanceOf(UnknownProviderException.class);
    }
}
