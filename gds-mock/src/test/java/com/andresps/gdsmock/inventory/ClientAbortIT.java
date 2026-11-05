package com.andresps.gdsmock.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.andresps.gdsmock.support.Http;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/** A client that hangs up mid-stream: generation stops, nothing is logged as an error, the server keeps serving. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "gds.world.volume=300000",
            "logging.level.com.andresps.gdsmock.inventory.infrastructure.adapter.in.web=DEBUG"
        })
@ActiveProfiles({"deterministic", "it"})
@ExtendWith(OutputCaptureExtension.class)
class ClientAbortIT {

    @Value("${local.server.port}")
    private int port;

    @Test
    @DisplayName("AC-12: a client disconnecting mid-stream stops generation and is not logged as an error")
    void clientAbort(CapturedOutput output) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream()
                    .write("GET /mock-alpha/flights HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            byte[] firstBytes = in.readNBytes(16 * 1024);
            assertThat(new String(firstBytes, StandardCharsets.US_ASCII)).startsWith("HTTP/1.1 200");
        }

        waitForLog(output, "Client disconnected from mock-alpha feed");

        assertThat(output.getAll()).doesNotContain(" ERROR ").doesNotContain(" WARN ");
        assertThat(new Http(port).get("/mock-alpha/flights?date=2026-11-06").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private static void waitForLog(CapturedOutput output, String message) throws InterruptedException, IOException {
        for (int i = 0; i < 100 && !output.getAll().contains(message); i++) {
            Thread.sleep(100);
        }
        assertThat(output.getAll()).contains(message);
    }
}
