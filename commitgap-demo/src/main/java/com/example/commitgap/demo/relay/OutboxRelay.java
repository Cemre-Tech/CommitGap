package com.example.commitgap.demo.relay;

import com.example.commitgap.core.model.Checkpoint;
import com.example.commitgap.demo.DemoProperties;
import com.example.commitgap.demo.control.CheckpointGate;
import com.example.commitgap.demo.events.EventPublisher;
import com.example.commitgap.demo.events.PublishResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Single outbox relay. Reads pending rows in commit order, publishes each one, and marks it SENT only
 * after a positive publisher confirm for a message that was not returned as unroutable. A crash
 * between the confirm and the mark leaves the row PENDING, so the same event (same event id) is
 * published again after restart: at-least-once delivery, which the consumer must tolerate.
 *
 * <p>Version 1 runs exactly one relay, so rows are not claimed or leased. Running several relays
 * against this table would publish rows concurrently and is not supported.
 *
 * <p>Failures are retried with capped exponential backoff, bounded per row by
 * {@code commitgap.relay-max-attempts}; a row that exhausts its attempts is PARKED and reported.
 */
@Component
@ConditionalOnProperty(prefix = "commitgap", name = "role", havingValue = "relay")
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long MAX_BACKOFF_MILLIS = 1000;

    private final JdbcTemplate jdbc;
    private final EventPublisher publisher;
    private final CheckpointGate checkpoints;
    private final RelayGate gate;
    private final long pollMillis;
    private final int maxAttempts;

    private volatile boolean running;
    private Thread worker;

    public OutboxRelay(JdbcTemplate jdbc, EventPublisher publisher, CheckpointGate checkpoints, RelayGate gate,
                       DemoProperties properties) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.checkpoints = checkpoints;
        this.gate = gate;
        this.pollMillis = properties.relayPollMillis();
        this.maxAttempts = properties.relayMaxAttempts();
    }

    record PendingRow(UUID eventId, UUID orderId, String payload, int attempts) {
    }

    @Override
    public void start() {
        running = true;
        worker = Thread.ofPlatform().name("outbox-relay").daemon(true).start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        int failures = 0;
        while (running) {
            try {
                if (!gate.isOpen()) {
                    sleep(pollMillis);
                    continue;
                }
                List<PendingRow> rows = jdbc.query(
                        "SELECT event_id, order_id, payload, attempts FROM outbox WHERE status = 'PENDING' ORDER BY seq LIMIT 20",
                        (rs, i) -> new PendingRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                                rs.getString(3), rs.getInt(4)));
                if (rows.isEmpty()) {
                    sleep(pollMillis);
                    continue;
                }
                boolean failed = false;
                for (PendingRow row : rows) {
                    if (!running || !gate.isOpen()) {
                        break;
                    }
                    if (!relay(row)) {
                        failed = true;
                        break; // keep commit order: retry this row before later ones
                    }
                }
                failures = failed ? failures + 1 : 0;
                if (failed) {
                    sleep(Math.min(MAX_BACKOFF_MILLIS, pollMillis << Math.min(failures, 6)));
                }
            } catch (RuntimeException e) {
                failures++;
                log.warn("relay iteration failed: {}", e.getMessage());
                sleep(Math.min(MAX_BACKOFF_MILLIS, pollMillis << Math.min(failures, 6)));
            }
        }
    }

    /** Returns true when the row was confirmed and marked as sent. */
    private boolean relay(PendingRow row) {
        PublishResult result = publisher.publish(row.eventId(), row.orderId(), row.payload(), "relay");
        if (result.confirmed()) {
            checkpoints.reach(Checkpoint.RELAY_AFTER_PUBLISHER_CONFIRM_BEFORE_OUTBOX_MARK,
                    Map.of("eventId", row.eventId().toString(), "orderId", row.orderId().toString()));
            jdbc.update("UPDATE outbox SET status = 'SENT', sent_at = clock_timestamp(), attempts = attempts + 1 "
                    + "WHERE event_id = ? AND status = 'PENDING'", row.eventId());
            return true;
        }
        int attempts = row.attempts() + 1;
        String status = attempts >= maxAttempts ? "PARKED" : "PENDING";
        jdbc.update("UPDATE outbox SET attempts = ?, last_error = ?, status = ? WHERE event_id = ? AND status = 'PENDING'",
                attempts, result.kind() + (result.detail() == null ? "" : ": " + result.detail()), status, row.eventId());
        if ("PARKED".equals(status)) {
            log.warn("outbox row {} parked after {} attempts", row.eventId(), attempts);
        }
        return false;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
