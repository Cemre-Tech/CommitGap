package com.example.commitgap.core.result;

import com.example.commitgap.core.invariant.InvariantResult;
import com.example.commitgap.core.model.Outcome;
import com.example.commitgap.core.scenario.Scenario;
import com.example.commitgap.core.scenario.Workload;
import java.time.Instant;
import java.util.List;

/**
 * The full record of one scenario executed against one strategy in its own environment.
 *
 * @param parentRunId  the compare/demo run this cell belongs to, or {@code null} for a single run
 * @param measurements {@code null} when no snapshot could be taken
 */
public record RunResult(
        String runId,
        String parentRunId,
        ScenarioSummary scenario,
        String strategy,
        Instant startedAt,
        Instant finishedAt,
        ObservationSummary observation,
        Outcome outcome,
        List<String> outcomeReasons,
        Outcome expected,
        Expectation expectation,
        List<InvariantResult> invariants,
        Measurements measurements,
        FaultExecution fault,
        List<TimelineEvent> timeline,
        EnvironmentInfo environment,
        List<String> obstacles) {

    public RunResult {
        outcomeReasons = List.copyOf(outcomeReasons);
        invariants = List.copyOf(invariants);
        timeline = List.copyOf(timeline);
        obstacles = List.copyOf(obstacles);
    }

    public record ScenarioSummary(
            String id,
            String description,
            String source,
            Workload workload,
            String fault,
            String recovery,
            List<String> assertions) {

        public static ScenarioSummary of(Scenario s) {
            return new ScenarioSummary(s.id(), s.description(), s.source(), s.workload(),
                    s.fault().map(f -> f.describe()).orElse("none"), s.recovery().action().id(),
                    s.assertions().stream().map(a -> a.id()).toList());
        }
    }

    /**
     * @param endReason why observation stopped: "settled", "timeout" or "skipped"
     */
    public record ObservationSummary(int timeoutSeconds, long observedMillis, String endReason) {
    }
}
