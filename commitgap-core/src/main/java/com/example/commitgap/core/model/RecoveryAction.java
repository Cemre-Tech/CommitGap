package com.example.commitgap.core.model;

import java.util.Arrays;
import java.util.Optional;

public enum RecoveryAction {
    /** Start the killed container again (same image, same configuration, same data). */
    RESTART("restart"),
    /** Re-enable the Toxiproxy link. */
    RESTORE_NETWORK("restore-network"),
    /** Do nothing; observe the system as the fault left it. */
    NONE("none");

    private final String id;

    RecoveryAction(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<RecoveryAction> fromId(String id) {
        return Arrays.stream(values()).filter(a -> a.id.equals(id)).findFirst();
    }

    public static String knownIds() {
        return String.join(", ", Arrays.stream(values()).map(RecoveryAction::id).toList());
    }

    @Override
    public String toString() {
        return id;
    }
}
