package com.example.commitgap.core.snapshot;

import java.time.Instant;

/** Everything the invariants are evaluated against. Business correctness comes only from these rows. */
public record LabSnapshot(ProducerSnapshot producer, ConsumerSnapshot consumer, BrokerSnapshot broker,
                          Instant capturedAt) {
}
