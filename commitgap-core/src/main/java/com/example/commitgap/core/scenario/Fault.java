package com.example.commitgap.core.scenario;

import com.example.commitgap.core.model.Checkpoint;
import com.example.commitgap.core.model.FaultAction;
import com.example.commitgap.core.model.FaultTarget;
import java.util.Optional;

/**
 * One fault. Only the fields meaningful for the action are set; the loader rejects the others.
 *
 * <ul>
 *   <li>{@code kill}: {@code checkpoint}, {@code occurrence} (the n-th time the target reaches the checkpoint)</li>
 *   <li>{@code duplicate-publish}: {@code occurrence} (the n-th published event), {@code copies}</li>
 *   <li>{@code network-cut}: {@code atOrder} (cut before this order is sent), {@code ordersDuringFault}</li>
 * </ul>
 */
public record Fault(
        FaultTarget target,
        FaultAction action,
        Optional<Checkpoint> checkpoint,
        int occurrence,
        int copies,
        int atOrder,
        int ordersDuringFault) {

    public String describe() {
        return switch (action) {
            case KILL -> "SIGKILL " + target.id() + " at " + checkpoint.map(Checkpoint::id).orElse("?")
                    + " (occurrence " + occurrence + ")";
            case DUPLICATE_PUBLISH -> "publish event #" + occurrence + " " + copies + " times with the same event id";
            case NETWORK_CUT -> "cut the publisher-to-broker link before order " + atOrder
                    + " for " + ordersDuringFault + " orders";
        };
    }
}
