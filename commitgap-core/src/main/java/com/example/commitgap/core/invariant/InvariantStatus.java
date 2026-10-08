package com.example.commitgap.core.invariant;

public enum InvariantStatus {
    /** The check held for the measured rows. */
    PASS,
    /** The rows show a violation. */
    FAIL,
    /** The rows do not settle the question, for example because work was still pending. */
    INCONCLUSIVE,
    /** The check does not apply to the strategy (for example, deduplication records without a deduplicating consumer). */
    NOT_APPLICABLE
}
