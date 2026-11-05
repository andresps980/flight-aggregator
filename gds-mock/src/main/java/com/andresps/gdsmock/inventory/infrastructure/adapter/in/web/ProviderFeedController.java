package com.andresps.gdsmock.inventory.infrastructure.adapter.in.web;

import com.andresps.gdsmock.inventory.domain.generator.WorldGenerator;
import com.andresps.gdsmock.inventory.domain.model.ProviderRecord;
import java.io.IOException;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * {@code GET /{providerId}/flights[?date=]}: streams one provider's records in its format while they are generated.
 * Chaos (latency, errors, outage) is applied before this controller by {@code ChaosInterceptor}.
 */
@RestController
public class ProviderFeedController {

    private static final Logger log = LoggerFactory.getLogger(ProviderFeedController.class);

    private final ProviderFormats formats;
    private final WorldGenerator generator;
    private final FeedRequestResolver resolver;

    public ProviderFeedController(ProviderFormats formats, WorldGenerator generator, FeedRequestResolver resolver) {
        this.formats = formats;
        this.generator = generator;
        this.resolver = resolver;
    }

    @GetMapping("/{providerId}/flights")
    public ResponseEntity<StreamingResponseBody> flights(
            @PathVariable String providerId, @RequestParam(required = false) String date) {
        FeedRequest request = resolver.resolve(providerId, date);
        ProviderFormat format = formats.get(request.provider());
        StreamingResponseBody body = out -> {
            try (Stream<ProviderRecord> records = request.date()
                    .map(day -> generator.records(request.provider(), day))
                    .orElseGet(() -> generator.records(request.provider()))) {
                format.write(records.iterator(), out);
            } catch (IOException e) {
                // Writing to the response only fails when the connection is gone: the client hung up (or timed out).
                // Generation stops with the stream; it is the client's choice, not a server error.
                log.debug("Client disconnected from {} feed: {}", request.provider(), e.getMessage());
            }
        };
        return ResponseEntity.ok().contentType(format.mediaType()).body(body);
    }
}
