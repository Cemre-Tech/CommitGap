package com.example.commitgap.core.result;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.commitgap.core.invariant.InvariantId;
import com.example.commitgap.core.invariant.InvariantResult;
import com.example.commitgap.core.invariant.InvariantStatus;
import com.example.commitgap.core.model.Outcome;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutcomeClassifierTest {

    private static final FaultExecution APPLIED = new FaultExecution(FaultExecution.Status.APPLIED, "kill", "producer",
            "producer.after-db-commit-before-publish", Instant.EPOCH, Map.of(), Instant.EPOCH,
            "exit code 137", "restart", Instant.EPOCH, null);

    @Test
    void allPassingChecksAreConsistent() {
        var c = OutcomeClassifier.classify(true, List.of(), APPLIED, List.of(result(InvariantStatus.PASS)));

        assertThat(c.outcome()).isEqualTo(Outcome.CONSISTENT);
    }

    @Test
    void aFailedCheckIsAViolation() {
        var c = OutcomeClassifier.classify(true, List.of(), APPLIED,
                List.of(result(InvariantStatus.PASS), result(InvariantStatus.FAIL)));

        assertThat(c.outcome()).isEqualTo(Outcome.VIOLATION_OBSERVED);
    }

    @Test
    void environmentProblemsMakeTheRunInconclusiveEvenWithFailedChecks() {
        var c = OutcomeClassifier.classify(true, List.of("consumer container did not become healthy"), APPLIED,
                List.of(result(InvariantStatus.FAIL)));

        assertThat(c.outcome()).isEqualTo(Outcome.INCONCLUSIVE);
        assertThat(c.reasons()).contains("consumer container did not become healthy");
    }

    @Test
    void anUnreachedCheckpointIsInconclusiveNotExpectedLoss() {
        FaultExecution notReached = new FaultExecution(FaultExecution.Status.CHECKPOINT_NOT_REACHED, "kill",
                "producer", "producer.after-db-commit-before-publish", null, Map.of(), null, null, null, null,
                "not reached within 30s");

        var c = OutcomeClassifier.classify(true, List.of(), notReached, List.of(result(InvariantStatus.FAIL)));

        assertThat(c.outcome()).isEqualTo(Outcome.INCONCLUSIVE);
    }

    @Test
    void notApplicableWins() {
        var c = OutcomeClassifier.classify(false, List.of(), FaultExecution.notApplicable("x", "relay"), List.of());

        assertThat(c.outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
    }

    @Test
    void noEvaluatedCheckIsNotConsistent() {
        var c = OutcomeClassifier.classify(true, List.of(), FaultExecution.notRequired(), List.of());

        assertThat(c.outcome()).isEqualTo(Outcome.INCONCLUSIVE);
    }

    @Test
    void anExpectedViolationMatchesButStaysAViolation() {
        Outcome measured = Outcome.VIOLATION_OBSERVED;

        assertThat(Expectation.compare(measured, Outcome.VIOLATION_OBSERVED)).isEqualTo(Expectation.MATCHED);
        assertThat(measured).isEqualTo(Outcome.VIOLATION_OBSERVED);
        assertThat(Expectation.compare(Outcome.CONSISTENT, Outcome.VIOLATION_OBSERVED))
                .isEqualTo(Expectation.NOT_MATCHED);
        assertThat(Expectation.compare(Outcome.INCONCLUSIVE, Outcome.CONSISTENT)).isEqualTo(Expectation.NOT_VERIFIED);
    }

    private static InvariantResult result(InvariantStatus status) {
        return new InvariantResult(InvariantId.STOCK_CHANGED_ONCE_PER_EVENT, status, "e", "a", "x", Map.of());
    }
}
