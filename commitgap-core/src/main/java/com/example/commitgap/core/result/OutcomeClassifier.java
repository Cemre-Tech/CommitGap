package com.example.commitgap.core.result;

import com.example.commitgap.core.invariant.InvariantResult;
import com.example.commitgap.core.invariant.InvariantStatus;
import com.example.commitgap.core.model.Outcome;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns measurement facts into an {@link Outcome}. The order of the rules matters:
 * <ol>
 *   <li>not applicable to the strategy → NOT_APPLICABLE</li>
 *   <li>any measurement obstacle (environment failure, fault not applied as specified, snapshot
 *       unreadable) → INCONCLUSIVE, even if some invariant happened to fail, because the run did
 *       not execute the scenario that was asked for</li>
 *   <li>any selected invariant FAIL → VIOLATION_OBSERVED</li>
 *   <li>any selected invariant INCONCLUSIVE → INCONCLUSIVE</li>
 *   <li>otherwise CONSISTENT</li>
 * </ol>
 */
public final class OutcomeClassifier {

    private OutcomeClassifier() {
    }

    public record Classification(Outcome outcome, List<String> reasons) {
        public Classification {
            reasons = List.copyOf(reasons);
        }
    }

    public static Classification classify(boolean applicable, List<String> obstacles, FaultExecution fault,
                                          List<InvariantResult> invariants) {
        if (!applicable) {
            return new Classification(Outcome.NOT_APPLICABLE,
                    List.of(fault == null || fault.problem() == null ? "scenario does not apply" : fault.problem()));
        }
        List<String> reasons = new ArrayList<>(obstacles);
        if (fault != null && !fault.satisfied()) {
            reasons.add("fault " + fault.status().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                    + (fault.problem() == null ? "" : ": " + fault.problem()));
        }
        if (!reasons.isEmpty()) {
            return new Classification(Outcome.INCONCLUSIVE, reasons);
        }
        List<String> failed = invariants.stream().filter(r -> r.status() == InvariantStatus.FAIL)
                .map(r -> r.id().id() + ": " + r.explanation()).toList();
        if (!failed.isEmpty()) {
            return new Classification(Outcome.VIOLATION_OBSERVED, failed);
        }
        List<String> open = invariants.stream().filter(r -> r.status() == InvariantStatus.INCONCLUSIVE)
                .map(r -> r.id().id() + ": " + r.explanation()).toList();
        if (!open.isEmpty()) {
            return new Classification(Outcome.INCONCLUSIVE, open);
        }
        if (invariants.isEmpty()) {
            return new Classification(Outcome.INCONCLUSIVE, List.of("no invariant was evaluated"));
        }
        return new Classification(Outcome.CONSISTENT,
                List.of("all selected checks held within the observation window"));
    }
}
