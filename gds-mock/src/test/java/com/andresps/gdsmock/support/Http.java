package com.andresps.gdsmock.support;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** Real HTTP calls for integration tests; never throws on 4xx/5xx so tests can assert error responses. */
public final class Http {

    private final RestClient client;

    public Http(int port) {
        this.client = RestClient.create("http://localhost:" + port);
    }

    public ResponseEntity<byte[]> get(String path) {
        return client.get()
                .uri(path)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {})
                .toEntity(byte[].class);
    }

    public ResponseEntity<String> getText(String path) {
        return client.get()
                .uri(path)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {})
                .toEntity(String.class);
    }

    public ResponseEntity<String> post(String path) {
        return client.post()
                .uri(path)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {})
                .toEntity(String.class);
    }

    public ResponseEntity<String> delete(String path) {
        return client.delete()
                .uri(path)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {})
                .toEntity(String.class);
    }
}
