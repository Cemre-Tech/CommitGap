package com.example.commitgap.core.snapshot;

import java.time.Instant;
import java.util.UUID;

/** A deduplication record of the idempotent consumer. */
public record ProcessedMessage(String consumerName, UUID eventId, Instant processedAt) {
}
