package com.example.commitgap.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Real HTTP protocol tests, including the locale bug and mutation followed by a failed response. */
class PublisherResponseToxicTest {

    @Test
    void timeoutProtocolStringsRemainValidUnderTurkishDefaultLocale() throws Exception {
        Locale previous = Locale.getDefault();
        try (Daemon daemon = new Daemon(false)) {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            PublisherResponseToxic toxic = daemon.client();
            toxic.drop();
            assertThat(toxic.dropped()).isTrue();
            assertThat(daemon.created.get().path("type").asString()).isEqualTo("timeout");
            assertThat(daemon.created.get().path("stream").asString()).isEqualTo("downstream");
            assertThat(daemon.created.get().path("attributes").path("timeout").asLong()).isZero();
            toxic.restore();
            assertThat(toxic.dropped()).isFalse();
            assertThat(daemon.deletes.get()).isEqualTo(1);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void creationThatMutatesThenReturnsAnErrorStillRemovesTheOwnedToxic() throws Exception {
        try (Daemon daemon = new Daemon(true)) {
            PublisherResponseToxic toxic = daemon.client();
            assertThatThrownBy(toxic::drop).isInstanceOf(IOException.class).hasMessageContaining("HTTP 503");
            assertThat(daemon.creates.get()).as("the daemon actually received and applied the request").isEqualTo(1);
            assertThat(daemon.ownedPresent.get()).isFalse();
            assertThat(daemon.deletes.get()).isEqualTo(1);
            toxic.restore(); // the outer fault driver's finally can safely clean up a second time
            assertThat(daemon.deletes.get()).isEqualTo(1);
        }
    }

    @Test
    void restoreIsIdempotentAndDoesNotDeleteUnrelatedToxics() throws Exception {
        try (Daemon daemon = new Daemon(false)) {
            PublisherResponseToxic toxic = daemon.client();
            toxic.restore();
            toxic.restore();
            assertThat(daemon.deletes.get()).isZero();
        }
    }

    private static final class Daemon implements AutoCloseable {
        private final HttpServer server;
        private final boolean rejectAfterMutation;
        private final AtomicBoolean ownedPresent = new AtomicBoolean();
        private final AtomicInteger creates = new AtomicInteger();
        private final AtomicInteger deletes = new AtomicInteger();
        private final AtomicReference<JsonNode> created = new AtomicReference<>();

        Daemon(boolean rejectAfterMutation) throws IOException {
            this.rejectAfterMutation = rejectAfterMutation;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/proxies/test", this::handle);
            server.start();
        }

        PublisherResponseToxic client() {
            return new PublisherResponseToxic(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test");
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                if ("GET".equals(method) && "/proxies/test".equals(path)) {
                    List<Map<String, Object>> toxics = new ArrayList<>();
                    toxics.add(Map.of("name", "foreign", "type", "latency", "stream", "upstream", "toxicity", 1.0,
                            "attributes", Map.of("latency", 50)));
                    if (ownedPresent.get()) {
                        toxics.add(Map.of("name", PublisherResponseToxic.NAME, "type", "timeout", "stream", "downstream",
                                "toxicity", 1.0, "attributes", Map.of("timeout", 0)));
                    }
                    respond(exchange, 200, Map.of("enabled", true, "toxics", toxics));
                } else if ("POST".equals(method) && "/proxies/test/toxics".equals(path)) {
                    JsonNode body = Json.MAPPER.readTree(exchange.getRequestBody());
                    created.set(body);
                    if (!"timeout".equals(body.path("type").asString())
                            || !"downstream".equals(body.path("stream").asString())) {
                        respond(exchange, 400, Map.of("error", "invalid protocol strings"));
                        return;
                    }
                    creates.incrementAndGet();
                    ownedPresent.set(true);
                    respond(exchange, rejectAfterMutation ? 503 : 200, Map.of("name", PublisherResponseToxic.NAME));
                } else if ("DELETE".equals(method) && ("/proxies/test/toxics/" + PublisherResponseToxic.NAME).equals(path)) {
                    deletes.incrementAndGet();
                    ownedPresent.set(false);
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    respond(exchange, 404, Map.of("error", "not found"));
                }
            }
        }

        private static void respond(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
            byte[] bytes = Json.COMPACT.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
