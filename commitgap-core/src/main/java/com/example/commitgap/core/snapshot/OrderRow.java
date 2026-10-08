package com.example.commitgap.core.snapshot;

import java.time.Instant;
import java.util.UUID;

/** A committed row in the producer's {@code orders} table. */
public record OrderRow(UUID orderId, UUID eventId, String sku, int quantity, Instant createdAt) {
}
