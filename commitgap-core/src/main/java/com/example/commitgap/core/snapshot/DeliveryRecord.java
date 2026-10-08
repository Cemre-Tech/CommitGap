package com.example.commitgap.core.snapshot;

import java.time.Instant;
import java.util.UUID;

/**
 * A delivery as seen by the consumer, logged on a separate autocommit connection before processing.
 *
 * @param outcome APPLIED, DUPLICATE_SKIPPED, FAILED, or {@code null} when the process died before recording one
 */
public record DeliveryRecord(long id, UUID eventId, boolean redelivered, String worker, String outcome,
                             Instant receivedAt) {
}
