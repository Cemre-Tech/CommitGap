package com.example.commitgap.core.invariant;

import java.util.List;
import java.util.Map;

/**
 * The evaluation of one invariant.
 *
 * @param expected    what the invariant requires, in measured units
 * @param actual      what the snapshot showed
 * @param explanation one or two sentences on why the status was chosen
 * @param evidence    named identity sets (event ids, order ids) backing the status; sorted for stable output
 */
public record InvariantResult(
        InvariantId id,
        InvariantStatus status,
        String expected,
        String actual,
        String explanation,
        Map<String, List<String>> evidence) {

    public InvariantResult {
        evidence = Map.copyOf(evidence);
    }
}
