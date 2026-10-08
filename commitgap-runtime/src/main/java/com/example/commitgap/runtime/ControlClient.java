package com.example.commitgap.runtime;

import com.example.commitgap.core.model.Checkpoint;
import com.example.commitgap.core.workload.PlannedOrder;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** HTTP client for the control endpoints of one demo process. Resolves the current host port per call. */
public final class ControlClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final DemoProcess process;

    ControlClient(DemoProcess process) {
        this.process = process;
    }

    public record Response(int status, JsonNode body) {
    }

    public boolean healthy() {
        try {
            return get("/control/health", Duration.ofSeconds(2)).status() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    public void armCheckpoint(Checkpoint checkpoint, int occurrence) {
        expectOk(post("/control/checkpoints/arm", Map.of("checkpoint", checkpoint.id(), "occurrence", occurrence)),
                "arm " + checkpoint.id());
    }

    public JsonNode checkpoints() throws IOException {
        return get("/control/checkpoints", Duration.ofSeconds(2)).body();
    }

    public void armDuplicatePublish(int occurrence, int copies) {
        expectOk(post("/control/faults/duplicate-publish", Map.of("occurrence", occurrence, "copies", copies)),
                "arm duplicate publish");
    }

    public void setRelayGate(boolean open) {
        expectOk(post("/control/relay/gate", Map.of("open", open)), "set relay gate");
    }

    /** Sends one order. Throws IOException when the producer is unreachable or dies during the request. */
    public Response createOrder(PlannedOrder order, Duration timeout) throws IOException {
        return send(HttpRequest.newBuilder(process.baseUri().resolve("/orders"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.COMPACT.writeValueAsString(Map.of(
                        "orderId", order.orderId().toString(),
                        "eventId", order.eventId().toString(),
                        "sku", order.sku(),
                        "quantity", order.quantity(),
                        "rollback", order.rollback()))))
                .build());
    }

    private Response get(String path, Duration timeout) throws IOException {
        return send(HttpRequest.newBuilder(process.baseUri().resolve(path)).timeout(timeout).GET().build());
    }

    private Response post(String path, Map<String, ?> body) {
        try {
            return send(HttpRequest.newBuilder(process.baseUri().resolve(path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.COMPACT.writeValueAsString(body)))
                    .build());
        } catch (IOException e) {
            throw new LabException(process.containerName() + " " + path + ": " + e.getMessage(), e);
        }
    }

    private Response send(HttpRequest request) throws IOException {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            JsonNode body = text == null || text.isBlank() ? null : Json.MAPPER.readTree(text);
            return new Response(response.statusCode(), body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private void expectOk(Response response, String what) {
        if (response.status() / 100 != 2) {
            throw new LabException(process.containerName() + ": " + what + " failed with HTTP " + response.status()
                    + " " + response.body());
        }
    }
}
