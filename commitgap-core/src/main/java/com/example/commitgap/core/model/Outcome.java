package com.example.commitgap.core.model;

import java.util.Arrays;
import java.util.Optional;

/** The measured state of one strategy under one scenario. Independent of what the scenario expected. */
public enum Outcome {
    /** The selected checks held within the observation window. Not a general exactly-once guarantee. */
    CONSISTENT,
    /** At least one invariant was measurably violated. */
    VIOLATION_OBSERVED,
    /** Not enough evidence: the environment failed, a checkpoint was not reached, or work was still pending. */
    INCONCLUSIVE,
    /** The scenario cannot be applied to the strategy (for example, a relay fault without a relay). */
    NOT_APPLICABLE;

    public static Optional<Outcome> fromId(String id) {
        return Arrays.stream(values()).filter(o -> o.name().equals(id)).findFirst();
    }
}
