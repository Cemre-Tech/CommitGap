package com.example.commitgap.core.snapshot;

import java.util.List;

/** Rows read from the producer database at the end of the observation window. */
public record ProducerSnapshot(List<OrderRow> orders, List<OutboxRow> outbox, List<PublishAttempt> publishAttempts) {

    public ProducerSnapshot {
        orders = List.copyOf(orders);
        outbox = List.copyOf(outbox);
        publishAttempts = List.copyOf(publishAttempts);
    }
}
