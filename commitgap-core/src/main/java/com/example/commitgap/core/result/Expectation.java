package com.example.commitgap.core.result;

import com.example.commitgap.core.model.Outcome;

/**
 * Whether the measured outcome equals the scenario's expectation. This is about the demonstration,
 * not about business correctness: a fragile strategy that shows its expected violation MATCHES, and
 * its outcome stays VIOLATION_OBSERVED.
 */
public enum Expectation {
    MATCHED,
    NOT_MATCHED,
    /** The measurement was inconclusive, so the expectation could not be checked. */
    NOT_VERIFIED;

    public static Expectation compare(Outcome measured, Outcome expected) {
        if (measured == Outcome.INCONCLUSIVE) {
            return NOT_VERIFIED;
        }
        return measured == expected ? MATCHED : NOT_MATCHED;
    }
}
