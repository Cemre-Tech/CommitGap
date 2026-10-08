package com.example.commitgap.core.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Named points in the demo processes where a fault can be applied deterministically. A process
 * reports that it reached a checkpoint only after the stage the name describes has really happened
 * (the transaction returned from commit, the broker returned a positive confirm), then waits for
 * the runner.
 */
public enum Checkpoint {

    PRODUCER_AFTER_DB_COMMIT_BEFORE_PUBLISH(
            "producer.after-db-commit-before-publish", ProcessRole.PRODUCER,
            "The producer transaction committed; the event has not left the producer process."),

    RELAY_AFTER_PUBLISHER_CONFIRM_BEFORE_OUTBOX_MARK(
            "relay.after-publisher-confirm-before-outbox-mark", ProcessRole.RELAY,
            "The broker positively confirmed a routable message; the outbox row is still pending."),

    CONSUMER_AFTER_BUSINESS_COMMIT_BEFORE_ACK(
            "consumer.after-business-commit-before-ack", ProcessRole.CONSUMER,
            "The consumer business transaction committed; the delivery has not been acknowledged.");

    private final String id;
    private final ProcessRole role;
    private final String meaning;

    Checkpoint(String id, ProcessRole role, String meaning) {
        this.id = id;
        this.role = role;
        this.meaning = meaning;
    }

    public String id() {
        return id;
    }

    public ProcessRole role() {
        return role;
    }

    public String meaning() {
        return meaning;
    }

    public static Optional<Checkpoint> fromId(String id) {
        return Arrays.stream(values()).filter(c -> c.id.equals(id)).findFirst();
    }

    public static String knownIds() {
        return String.join(", ", Arrays.stream(values()).map(Checkpoint::id).toList());
    }

    @Override
    public String toString() {
        return id;
    }
}
