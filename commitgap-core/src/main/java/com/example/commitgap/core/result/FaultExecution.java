package com.example.commitgap.core.result;

import java.time.Instant;
import java.util.Map;

/**
 * What the runner actually did about the scenario's fault. Recorded by the runner itself, so it
 * survives the death of the target process.
 *
 * @param checkpointContext what the target reported when it reached the checkpoint (event id, order id, ...)
 * @param verification      how the fault was confirmed, e.g. "container not running, exit code 137"
 */
public record FaultExecution(
        Status status,
        String description,
        String target,
        String checkpoint,
        Instant checkpointReachedAt,
        Map<String, String> checkpointContext,
        Instant appliedAt,
        String verification,
        String recovery,
        Instant recoveredAt,
        String problem) {

    public enum Status {
        /** The scenario has no fault. */
        NOT_REQUIRED,
        /** The fault was applied and verified, and the recovery (if any) completed. */
        APPLIED,
        /** The target never reached the checkpoint within the deadline. */
        CHECKPOINT_NOT_REACHED,
        /** The fault was attempted but could not be verified (for example, the container kept running). */
        NOT_VERIFIED,
        /** The fault was applied but the recovery action failed. */
        RECOVERY_FAILED,
        /** The scenario did not apply to the strategy. */
        NOT_APPLICABLE
    }

    public FaultExecution {
        checkpointContext = checkpointContext == null ? Map.of() : Map.copyOf(checkpointContext);
    }

    public static FaultExecution notRequired() {
        return new FaultExecution(Status.NOT_REQUIRED, "no fault", null, null, null, Map.of(), null, null,
                null, null, null);
    }

    public static FaultExecution notApplicable(String description, String target) {
        return new FaultExecution(Status.NOT_APPLICABLE, description, target, null, null, Map.of(), null, null,
                null, null, "target '" + target + "' does not exist in this strategy");
    }

    public boolean satisfied() {
        return status == Status.NOT_REQUIRED || status == Status.APPLIED;
    }
}
