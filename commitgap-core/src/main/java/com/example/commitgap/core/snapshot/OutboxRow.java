package com.example.commitgap.core.snapshot;

import java.time.Instant;
import java.util.UUID;

/**
 * A row in the producer's {@code outbox} table.
 *
 * @param status PENDING, SENT or PARKED (gave up after the bounded number of attempts)
 */
public record OutboxRow(UUID eventId, UUID orderId, String status, int attempts, Instant createdAt, Instant sentAt) {

    public boolean pending() {
        return "PENDING".equals(status);
    }

    public boolean parked() {
        return "PARKED".equals(status);
    }
}
