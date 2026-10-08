package com.example.commitgap.core.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * The delivery strategies CommitGap compares. All of them use the same event identifiers, the same
 * broker durability settings (durable queue, persistent messages, publisher confirms, mandatory
 * publishing) and the same workload; they differ only in where the event is recorded and whether
 * the consumer deduplicates.
 */
public enum Strategy {

    /** Commit the order, then publish. No durable record of the pending event. Non-idempotent consumer. */
    NAIVE_DUAL_WRITE("naive-dual-write", false, false),

    /** Order and outbox row commit in one transaction; a separate relay publishes. Non-idempotent consumer. */
    TRANSACTIONAL_OUTBOX("transactional-outbox", true, false),

    /** Transactional outbox plus a consumer that deduplicates on (consumer_name, event_id). */
    OUTBOX_IDEMPOTENT("outbox-idempotent", true, true);

    private final String id;
    private final boolean usesOutboxRelay;
    private final boolean idempotentConsumer;

    Strategy(String id, boolean usesOutboxRelay, boolean idempotentConsumer) {
        this.id = id;
        this.usesOutboxRelay = usesOutboxRelay;
        this.idempotentConsumer = idempotentConsumer;
    }

    public String id() {
        return id;
    }

    public boolean usesOutboxRelay() {
        return usesOutboxRelay;
    }

    public boolean idempotentConsumer() {
        return idempotentConsumer;
    }

    public static Optional<Strategy> fromId(String id) {
        return Arrays.stream(values()).filter(s -> s.id.equals(id)).findFirst();
    }

    public static String knownIds() {
        return String.join(", ", Arrays.stream(values()).map(Strategy::id).toList());
    }

    @Override
    public String toString() {
        return id;
    }
}
