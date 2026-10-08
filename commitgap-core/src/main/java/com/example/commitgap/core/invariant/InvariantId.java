package com.example.commitgap.core.invariant;

import java.util.Arrays;
import java.util.Optional;

/** The checks CommitGap can evaluate against producer and consumer database snapshots. */
public enum InvariantId {

    COMMITTED_ORDER_HAS_BUSINESS_EFFECT(
            "committed-order-has-business-effect",
            "Every committed order has a stock movement carrying the order's own event id."),

    STOCK_CHANGED_ONCE_PER_EVENT(
            "stock-changed-once-per-event",
            "Each event changed stock exactly once, by exactly the ordered quantity, for the right order."),

    ROLLED_BACK_ORDER_HAS_NO_EFFECT(
            "rolled-back-order-has-no-effect",
            "No stock movement exists for an order that did not commit, or for an unknown event id."),

    STOCK_MATCHES_COMMITTED_ORDERS(
            "stock-matches-committed-orders",
            "Final stock equals initial stock minus the quantities of committed orders, and matches the movement ledger."),

    DEDUP_RECORD_MATCHES_BUSINESS_EFFECT(
            "dedup-record-matches-business-effect",
            "Every processed-message record has exactly one business effect and vice versa (idempotent consumer only)."),

    NO_PENDING_WORK_AFTER_RECOVERY(
            "no-pending-work-after-recovery",
            "After recovery no outbox row is pending or parked and no message is queued, unacknowledged or dead-lettered.");

    private final String id;
    private final String description;

    InvariantId(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public static Optional<InvariantId> fromId(String id) {
        return Arrays.stream(values()).filter(i -> i.id.equals(id)).findFirst();
    }

    public static String knownIds() {
        return String.join(", ", Arrays.stream(values()).map(InvariantId::id).toList());
    }

    @Override
    public String toString() {
        return id;
    }
}
