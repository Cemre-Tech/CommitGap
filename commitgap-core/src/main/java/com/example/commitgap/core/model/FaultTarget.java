package com.example.commitgap.core.model;

import java.util.Arrays;
import java.util.Optional;

/** What a fault is applied to. Targets are always processes or links created by the lab itself. */
public enum FaultTarget {
    PRODUCER("producer"),
    RELAY("relay"),
    CONSUMER("consumer"),
    /** Whichever process publishes events in the strategy: the producer, or the relay for outbox strategies. */
    PUBLISHER("publisher"),
    /** The network path from the publishing processes to the broker, routed through Toxiproxy. */
    BROKER_LINK("broker-link");

    private final String id;

    FaultTarget(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** The process this target resolves to in the strategy; empty when it is not a process or does not exist. */
    public Optional<ProcessRole> processIn(Strategy strategy) {
        return switch (this) {
            case PRODUCER -> Optional.of(ProcessRole.PRODUCER);
            case CONSUMER -> Optional.of(ProcessRole.CONSUMER);
            case RELAY -> strategy.usesOutboxRelay() ? Optional.of(ProcessRole.RELAY) : Optional.empty();
            case PUBLISHER -> Optional.of(strategy.usesOutboxRelay() ? ProcessRole.RELAY : ProcessRole.PRODUCER);
            case BROKER_LINK -> Optional.empty();
        };
    }

    public boolean existsIn(Strategy strategy) {
        return this == BROKER_LINK || processIn(strategy).isPresent();
    }

    public static Optional<FaultTarget> fromId(String id) {
        return Arrays.stream(values()).filter(t -> t.id.equals(id)).findFirst();
    }

    public static String knownIds() {
        return String.join(", ", Arrays.stream(values()).map(FaultTarget::id).toList());
    }

    @Override
    public String toString() {
        return id;
    }
}
