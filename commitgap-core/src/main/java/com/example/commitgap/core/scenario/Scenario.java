package com.example.commitgap.core.scenario;

import com.example.commitgap.core.invariant.InvariantId;
import com.example.commitgap.core.model.Outcome;
import com.example.commitgap.core.model.Strategy;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A validated scenario file. Instances are only created by {@link ScenarioLoader}, which rejects
 * anything incomplete or meaningless, so consumers can rely on the cross-field rules documented there.
 *
 * @param fault    empty for scenarios without a fault (happy path)
 * @param recovery {@link Recovery#none()} when the scenario has no fault
 */
public record Scenario(
        int schemaVersion,
        String id,
        String description,
        Workload workload,
        Optional<Fault> fault,
        Recovery recovery,
        Observation observation,
        List<InvariantId> assertions,
        Map<Strategy, Outcome> expectedByStrategy,
        String source) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public Scenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workload, "workload");
        Objects.requireNonNull(fault, "fault");
        Objects.requireNonNull(recovery, "recovery");
        Objects.requireNonNull(observation, "observation");
        assertions = List.copyOf(assertions);
        expectedByStrategy = Map.copyOf(expectedByStrategy);
    }

    /** Whether the scenario's fault can be applied to the strategy. Scenarios without a fault apply everywhere. */
    public boolean isApplicableTo(Strategy strategy) {
        return fault.map(f -> f.target().existsIn(strategy)).orElse(true);
    }

    public Outcome expectedFor(Strategy strategy) {
        return expectedByStrategy.get(strategy);
    }
}
