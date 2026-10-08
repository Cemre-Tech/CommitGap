package com.example.commitgap.core.model;

import java.util.Arrays;
import java.util.Optional;

public enum FaultAction {
    /** SIGKILL the target container once it has reached the checkpoint. Not a graceful shutdown. */
    KILL("kill"),
    /** Cut the publisher-to-broker link in Toxiproxy while orders keep arriving. */
    NETWORK_CUT("network-cut"),
    /** Make the publisher send one event several times with the same event id. */
    DUPLICATE_PUBLISH("duplicate-publish");

    private final String id;

    FaultAction(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<FaultAction> fromId(String id) {
        return Arrays.stream(values()).filter(a -> a.id.equals(id)).findFirst();
    }

    public static String knownIds() {
        return String.join(", ", Arrays.stream(values()).map(FaultAction::id).toList());
    }

    @Override
    public String toString() {
        return id;
    }
}
