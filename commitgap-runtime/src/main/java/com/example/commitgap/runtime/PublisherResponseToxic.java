package com.example.commitgap.runtime;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The owned downstream toxic, using literal protocol strings. The pinned Toxiproxy Java client
 * lowercases enum names in the JVM's default locale, which makes TIMEOUT invalid in Turkish locales.
 */
final class PublisherResponseToxic {

    static final String NAME = "commitgap-confirm-loss";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI api;
    private final String proxyPath;

    PublisherResponseToxic(URI api, String proxy) {
        this.api = api;
        this.proxyPath = "/proxies/" + proxy;
    }

    void drop() throws IOException {
        try {
            request("POST", proxyPath + "/toxics", Map.of("name", NAME, "type", "timeout",
                    "stream", "downstream", "toxicity", 1.0, "attributes", Map.of("timeout", 0)));
        } catch (IOException | RuntimeException mutationFailure) {
            // The server may have applied the mutation even if its response was lost or rejected.
            try {
                restore();
            } catch (IOException | RuntimeException cleanupFailure) {
                mutationFailure.addSuppressed(cleanupFailure);
            }
            throw mutationFailure;
        }
    }

    boolean dropped() throws IOException {
        JsonNode proxy = proxy();
        if (!proxy.path("enabled").asBoolean(false)) {
            return false;
        }
        for (JsonNode toxic : proxy.path("toxics")) {
            if (NAME.equals(toxic.path("name").asString()) && "timeout".equals(toxic.path("type").asString())
                    && "downstream".equals(toxic.path("stream").asString())
                    && toxic.path("toxicity").asDouble(-1) == 1.0
                    && toxic.path("attributes").path("timeout").asLong(-1) == 0) {
                return true;
            }
        }
        return false;
    }

    /** Read actual ownership, then remove only our toxic. Safe even if the create response was lost. */
    void restore() throws IOException {
        for (JsonNode toxic : proxy().path("toxics")) {
            if (NAME.equals(toxic.path("name").asString())) {
                request("DELETE", proxyPath + "/toxics/" + NAME, null);
                return;
            }
        }
    }

    private JsonNode proxy() throws IOException {
        JsonNode proxy = request("GET", proxyPath, null);
        if (proxy == null || !proxy.path("toxics").isArray() || !proxy.path("enabled").isBoolean()) {
            throw new IOException("Toxiproxy did not return the proxy's enabled state and toxic list");
        }
        return proxy;
    }

    private JsonNode request(String method, String path, Map<String, ?> body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(api.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(Json.COMPACT.writeValueAsString(body))).build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if ("DELETE".equals(method) && response.statusCode() == 404) {
                return null; // already removed since the ownership read
            }
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Toxiproxy " + method + " " + path + " returned HTTP " + response.statusCode());
            }
            return response.body().isBlank() ? null : Json.MAPPER.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while controlling the downstream toxic", e);
        }
    }
}
