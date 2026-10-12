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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox relay workers. Each transaction locks one pending row, publishes it, and marks it SENT only
 * after a positive publisher confirm for a message that was not returned as unroutable. A crash
 * between the confirm and the mark leaves the row PENDING, so the same event (same event id) is
 * published again after restart: at-least-once delivery, which the consumer must tolerate.
 *
 * <p>FOR UPDATE SKIP LOCKED excludes a row while another worker publishes it. The lock is held until
 * the SENT update commits; a killed process rolls back and releases it. Multiple workers may publish
 * different rows concurrently, so global commit order is not guaranteed. The lab uses one process
 * with configurable worker threads, not independently managed relay processes or leases.
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
    private final int workerCount;
    private final TransactionTemplate tx;

    private volatile boolean running;
    private final List<Thread> workers = new java.util.ArrayList<>();

    public OutboxRelay(JdbcTemplate jdbc, EventPublisher publisher, CheckpointGate checkpoints, RelayGate gate,
                       DemoProperties properties, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.checkpoints = checkpoints;
        this.gate = gate;
        this.pollMillis = properties.relayPollMillis();
        this.maxAttempts = properties.relayMaxAttempts();
        this.workerCount = properties.relayWorkers();
        this.tx = tx;
    }

    record PendingRow(UUID eventId, UUID orderId, String payload, int attempts) {
    }

    @Override
    public void start() {
        running = true;
        for (int i = 1; i <= workerCount; i++) {
            workers.add(Thread.ofPlatform().name("outbox-relay-" + i).daemon(true).start(this::loop));
        }
    }

    @Override
    public void stop() {
        running = false;
        workers.forEach(Thread::interrupt);
        workers.clear();
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
                Attempt attempt = relayNext();
                if (attempt == Attempt.EMPTY) {
                    sleep(pollMillis);
                    continue;
                }
                failures = attempt == Attempt.FAILED ? failures + 1 : 0;
                if (attempt == Attempt.FAILED) {
                    sleep(Math.min(MAX_BACKOFF_MILLIS, pollMillis << Math.min(failures, 6)));
                }
            } catch (RuntimeException e) {
                failures++;
                log.warn("relay iteration failed: {}", e.getMessage());
                sleep(Math.min(MAX_BACKOFF_MILLIS, pollMillis << Math.min(failures, 6)));
            }
        }
    }

    enum Attempt { EMPTY, SENT, FAILED }

    /** One row and one transaction; visible to the database concurrency regression test. */
    Attempt relayNext() {
        return tx.execute(status -> {
            List<PendingRow> rows = jdbc.query(
                    "SELECT event_id, order_id, payload, attempts FROM outbox WHERE status = 'PENDING' "
                            + "ORDER BY seq LIMIT 1 FOR UPDATE SKIP LOCKED",
                    (rs, i) -> new PendingRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getString(3), rs.getInt(4)));
            if (rows.isEmpty()) {
                return Attempt.EMPTY;
            }
            PendingRow row = rows.getFirst();
            checkpoints.reach(Checkpoint.RELAY_AFTER_ROW_LOCK_BEFORE_PUBLISH,
                    Map.of("eventId", row.eventId().toString(), "orderId", row.orderId().toString(),
                            "worker", Thread.currentThread().getName(), "transactionState", "row locked"));
            return relay(row) ? Attempt.SENT : Attempt.FAILED;
        });
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
        if (result.kind() == PublishResult.Kind.TIMEOUT) {
            checkpoints.reach(Checkpoint.RELAY_AFTER_PUBLISHER_TIMEOUT_BEFORE_RETRY,
                    Map.of("eventId", row.eventId().toString(), "orderId", row.orderId().toString(),
                            "publishResult", "TIMEOUT"));
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
