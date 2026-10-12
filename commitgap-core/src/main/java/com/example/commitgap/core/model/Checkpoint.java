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

    RELAY_AFTER_ROW_LOCK_BEFORE_PUBLISH(
            "relay.after-row-lock-before-publish", ProcessRole.RELAY,
            "The relay transaction locked a pending row; the event has not been published."),

    RELAY_AFTER_BROKER_CONFIRM_BEFORE_PUBLISH_RESULT(
            "relay.after-broker-confirm-before-publish-result", ProcessRole.RELAY,
            "The publisher observed a positive routable broker confirm; no publish result has been recorded or returned to the relay."),

    RELAY_AFTER_PUBLISHER_CONFIRM_BEFORE_OUTBOX_MARK(
            "relay.after-publisher-confirm-before-outbox-mark", ProcessRole.RELAY,
            "The broker positively confirmed a routable message; the outbox row is still pending."),

    RELAY_AFTER_PUBLISHER_TIMEOUT_BEFORE_RETRY(
            "relay.after-publisher-timeout-before-retry", ProcessRole.RELAY,
            "The publisher's real confirm wait timed out and was recorded; the relay has not retried the pending event."),

    CONSUMER_AFTER_BUSINESS_WRITE_BEFORE_COMMIT(
            "consumer.after-business-write-before-commit", ProcessRole.CONSUMER,
            "The stock and movement writes (and dedup insert, when enabled) are still in an uncommitted transaction."),

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
