package com.example.commitgap.runtime;

import com.example.commitgap.core.snapshot.BrokerSnapshot;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import tools.jackson.databind.JsonNode;

/**
 * Reads broker state.
 *
 * <p>Queue depth (ready, unacknowledged, dead-lettered) comes from {@code rabbitmqctl list_queues}
 * inside the broker container, which reports the current state. The management API is not used for
 * depth because it serves periodically sampled statistics and was observed to report an empty queue
 * for several seconds while messages were waiting.
 *
 * <p>Cumulative counters (published, delivered, redelivered) only exist in the management API's
 * sampled {@code message_stats}; they are reported as -1 when absent and are explanatory only.
 */
final class BrokerProbe {

    static final String QUEUE = "commitgap.order-created";
    static final String DEAD_LETTER_QUEUE = "commitgap.order-created.dlq";

    @FunctionalInterface
    interface CommandRunner {
        /** Runs a command in the broker container and returns stdout, or throws when it fails. */
        String run(String... command) throws IOException;
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final CommandRunner exec;
    private final URI managementUri;
    private final String authorization;

    BrokerProbe(CommandRunner exec, String managementUrl, String user, String password) {
        this.exec = exec;
        this.managementUri = URI.create(managementUrl.endsWith("/") ? managementUrl : managementUrl + "/");
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    record Depth(long ready, long unacknowledged, long deadLettered) {
        long backlog() {
            return ready + unacknowledged;
        }
    }

    /** Current queue depth, straight from the broker. */
    Depth depth() throws IOException {
        String out = exec.run("rabbitmqctl", "list_queues", "--quiet", "--formatter", "json",
                "name", "messages_ready", "messages_unacknowledged");
        JsonNode queues = Json.MAPPER.readTree(out);
        Long ready = null;
        long unacked = 0;
        long dead = 0;
        for (JsonNode q : queues) {
            String name = q.path("name").asString();
            if (QUEUE.equals(name)) {
                ready = q.path("messages_ready").asLong(0);
                unacked = q.path("messages_unacknowledged").asLong(0);
            } else if (DEAD_LETTER_QUEUE.equals(name)) {
                dead = q.path("messages_ready").asLong(0) + q.path("messages_unacknowledged").asLong(0);
            }
        }
        if (ready == null) {
            throw new IOException("queue " + QUEUE + " does not exist");
        }
        return new Depth(ready, unacked, dead);
    }

    BrokerSnapshot snapshot() {
        Depth depth;
        try {
            depth = depth();
        } catch (IOException | RuntimeException e) {
            return BrokerSnapshot.unavailable(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        long published = -1;
        long deliveries = -1;
        long redeliveries = -1;
        try {
            JsonNode stats = managementQueue().path("message_stats");
            if (!stats.isMissingNode()) {
                published = stats.path("publish").asLong(-1);
                deliveries = stats.path("deliver_get").asLong(-1);
                redeliveries = stats.path("redeliver").asLong(-1);
            }
        } catch (IOException | RuntimeException e) {
            // counters stay "not measured"
        }
        return new BrokerSnapshot(true, depth.ready(), depth.unacknowledged(), depth.deadLettered(), published,
                deliveries, redeliveries, null);
    }

    private JsonNode managementQueue() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(managementUri.resolve("api/queues/%2F/" + QUEUE))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", authorization)
                .GET()
                .build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("management API returned HTTP " + response.statusCode());
            }
            return Json.MAPPER.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
