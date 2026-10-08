package com.example.commitgap.core.snapshot;

import java.time.Instant;
import java.util.UUID;

/** A business effect: one row in the consumer's {@code stock_movement} ledger. */
public record StockMovement(long id, UUID eventId, UUID orderId, String sku, int delta, Instant appliedAt) {
}
