package com.example.commitgap.core.model;

import java.util.Arrays;
import java.util.Optional;

/** A separately running process (container) of the demo application. */
public enum ProcessRole {
    PRODUCER("producer"),
    RELAY("relay"),
    CONSUMER("consumer");

    private final String id;

    ProcessRole(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean runsIn(Strategy strategy) {
        return this != RELAY || strategy.usesOutboxRelay();
    }

    public static Optional<ProcessRole> fromId(String id) {
        return Arrays.stream(values()).filter(r -> r.id.equals(id)).findFirst();
    }

    @Override
    public String toString() {
        return id;
    }
}
