package com.example.commitgap.adapter;

import com.example.commitgap.core.invariant.InvariantEvaluator;
import com.example.commitgap.core.invariant.InvariantId;
import com.example.commitgap.core.invariant.InvariantResult;
import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.result.Measurements;
import com.example.commitgap.core.result.OutcomeClassifier;
import com.example.commitgap.core.snapshot.OrderRow;
import com.example.commitgap.core.workload.WorkloadPlan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Evaluates the existing identity-based stock/order invariants against an external application's data. */
public final class ExternalApplicationVerifier {
    private ExternalApplicationVerifier() {
    }

    public record Verification(OutcomeClassifier.Classification classification, List<InvariantResult> invariants,
                               Measurements measurements) {
        public Verification {
            invariants = List.copyOf(invariants);
        }
    }

    /** Captures once and checks all core invariants; no expectation is used to determine the outcome. */
    public static Verification verify(WorkloadPlan plan, Strategy strategy, ExternalApplicationAdapter adapter) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(adapter, "adapter");
        ExternalObservation observation;
        try {
            observation = Objects.requireNonNull(adapter.capture(plan), "observation");
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Verification(OutcomeClassifier.classify(true,
                    List.of("External snapshot read failed (" + e.getClass().getSimpleName() + ")."), null,
                    List.of()), List.of(), null);
        }
        List<String> obstacles = new ArrayList<>(observation.obstacles());
        if (!observation.workloadCompleted()) {
            obstacles.add("The external application did not complete the planned workload.");
        }
        if (!observation.quiescent()) {
            obstacles.add("External writers were not quiescent during snapshot capture.");
        }
        // The core compares committed orders to effects. Also verify coverage here so an adapter
        // returning no rows cannot report CONSISTENT for a workload it never actually executed.
        var committed = observation.snapshot().producer().orders().stream().map(OrderRow::orderId)
                .collect(Collectors.toSet());
        var absent = plan.orders().stream().filter(o -> !o.rollback() && !committed.contains(o.orderId()))
                .map(o -> o.orderId().toString()).toList();
        if (!absent.isEmpty()) {
            obstacles.add("Planned non-rollback orders absent from the snapshot: " + String.join(", ", absent));
        }
        List<InvariantResult> invariants = new InvariantEvaluator(plan, strategy, observation.snapshot())
                .evaluate(Arrays.asList(InvariantId.values()));
        return new Verification(OutcomeClassifier.classify(true, obstacles, null, invariants), invariants,
                Measurements.of(plan, strategy, observation.snapshot()));
    }
}
