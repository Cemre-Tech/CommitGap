package com.example.commitgap.core.workload;

import java.util.UUID;

/**
 * One order of the workload as the runner intends to send it.
 *
 * @param number     1-based position in the workload
 * @param rollback   whether the producer is asked to roll this order back deliberately
 */
public record PlannedOrder(int number, UUID orderId, UUID eventId, String sku, int quantity, boolean rollback) {
}
