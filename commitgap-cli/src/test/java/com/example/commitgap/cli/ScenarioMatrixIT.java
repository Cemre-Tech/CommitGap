package com.example.commitgap.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.commitgap.core.invariant.InvariantId;
import com.example.commitgap.core.invariant.InvariantResult;
import com.example.commitgap.core.invariant.InvariantStatus;
import com.example.commitgap.core.model.Outcome;
import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.result.Expectation;
import com.example.commitgap.core.result.FaultExecution;
import com.example.commitgap.core.result.RunResult;
import com.example.commitgap.core.result.TimelineEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Runs every bundled scenario against every strategy in real containers and checks both the
 * classification and the evidence behind it. Nothing here is mocked; each expected result has to
 * be measured.
 */
class ScenarioMatrixIT {

    @Test
    void happyPathIsConsistentForAllStrategies() {
        for (Strategy s : Strategy.values()) {
            RunResult r = run("happy-path", s);
            assertThat(r.outcome()).as(LabSupport.describe(r)).isEqualTo(Outcome.CONSISTENT);
            assertThat(r.measurements().committedOrders()).isEqualTo(18);
            assertThat(r.measurements().uncommittedOrders()).isEqualTo(2);
            assertThat(r.measurements().finalStock()).isEqualTo(82);
        }
    }

    @Test
    void producerCommitGapLosesTheEventOnlyInTheNaiveStrategy() {
        RunResult naive = run("crash-after-commit", Strategy.NAIVE_DUAL_WRITE);
        assertKilled(naive);
        assertThat(naive.outcome()).as(LabSupport.describe(naive)).isEqualTo(Outcome.VIOLATION_OBSERVED);
        assertThat(naive.expectation()).isEqualTo(Expectation.MATCHED);
        String lostEvent = naive.fault().checkpointContext().get("eventId");
        InvariantResult committed = invariant(naive, InvariantId.COMMITTED_ORDER_HAS_BUSINESS_EFFECT);
        assertThat(committed.status()).isEqualTo(InvariantStatus.FAIL);
        assertThat(committed.evidence().get("missingWithoutPendingRecord"))
                .as("the missing effect is exactly the event the producer held at the checkpoint")
                .containsExactly(lostEvent);

        for (Strategy outbox : List.of(Strategy.TRANSACTIONAL_OUTBOX, Strategy.OUTBOX_IDEMPOTENT)) {
            RunResult r = run("crash-after-commit", outbox);
            assertKilled(r);
            assertThat(r.outcome()).as(LabSupport.describe(r)).isEqualTo(Outcome.CONSISTENT);
            assertThat(r.timeline()).extracting(TimelineEvent::kind).contains("relay-barrier-opened");
            assertThat(r.timeline()).anyMatch(e -> "business-effect".equals(e.kind())
                    && r.fault().checkpointContext().get("eventId").equals(e.attributes().get("eventId")));
        }
    }

    @Test
    void relayConfirmGapRepublishesTheSameEvent() {
        RunResult naive = run("relay-confirm-gap", Strategy.NAIVE_DUAL_WRITE);
        assertThat(naive.outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(naive.expectation()).isEqualTo(Expectation.MATCHED);

        RunResult outbox = run("relay-confirm-gap", Strategy.TRANSACTIONAL_OUTBOX);
        assertKilled(outbox);
        assertThat(outbox.outcome()).as(LabSupport.describe(outbox)).isEqualTo(Outcome.VIOLATION_OBSERVED);
        String event = outbox.fault().checkpointContext().get("eventId");
        assertThat(invariant(outbox, InvariantId.STOCK_CHANGED_ONCE_PER_EVENT).evidence().get("duplicatedEventIds"))
                .containsExactly(event + " x2");
        assertThat(confirmedPublishes(outbox, event)).isEqualTo(2);

        RunResult idempotent = run("relay-confirm-gap", Strategy.OUTBOX_IDEMPOTENT);
        assertKilled(idempotent);
        assertThat(idempotent.outcome()).as(LabSupport.describe(idempotent)).isEqualTo(Outcome.CONSISTENT);
        String event2 = idempotent.fault().checkpointContext().get("eventId");
        assertThat(confirmedPublishes(idempotent, event2)).as("the relay really published twice").isEqualTo(2);
        assertThat(idempotent.timeline()).anyMatch(e -> "delivery".equals(e.kind())
                && e.message().contains("DUPLICATE_SKIPPED") && event2.equals(e.attributes().get("eventId")));
    }

    @Test
    void duplicateDeliveryLeavesOneEffectOnlyWithTheIdempotentConsumer() {
        for (Strategy s : Strategy.values()) {
            RunResult r = run("duplicate-delivery", s);
            assertThat(r.fault().status()).as(LabSupport.describe(r)).isEqualTo(FaultExecution.Status.APPLIED);
            assertThat(r.outcome()).as(LabSupport.describe(r)).isEqualTo(r.expected());
            String event = r.fault().checkpointContext().get("eventId");
            assertThat(confirmedPublishes(r, event)).isEqualTo(3);
            long effects = r.timeline().stream().filter(e -> "business-effect".equals(e.kind())
                    && event.equals(e.attributes().get("eventId"))).count();
            assertThat(effects).isEqualTo(s.idempotentConsumer() ? 1 : 3);
        }
    }

    @Test
    void consumerAckGapIsRedeliveredAndOnlyTheIdempotentConsumerSkipsIt() {
        for (Strategy s : Strategy.values()) {
            RunResult r = run("consumer-ack-gap", s);
            assertKilled(r);
            assertThat(r.outcome()).as(LabSupport.describe(r)).isEqualTo(r.expected());
            String event = r.fault().checkpointContext().get("eventId");
            assertThat(r.timeline()).as("the broker redelivered the unacknowledged message")
                    .anyMatch(e -> "delivery".equals(e.kind()) && e.message().contains("redelivered")
                            && event.equals(e.attributes().get("eventId")));
        }
    }

    @Test
    void brokerOutageLosesEventsOnlyWithoutAnOutbox() {
        RunResult naive = run("broker-outage", Strategy.NAIVE_DUAL_WRITE);
        assertThat(naive.fault().status()).isEqualTo(FaultExecution.Status.APPLIED);
        assertThat(naive.outcome()).as(LabSupport.describe(naive)).isEqualTo(Outcome.VIOLATION_OBSERVED);
        // Orders 8..12 were accepted during the outage; 14 is a planned rollback outside the window.
        assertThat(invariant(naive, InvariantId.COMMITTED_ORDER_HAS_BUSINESS_EFFECT)
                .evidence().get("missingWithoutPendingRecord")).hasSize(5);

        for (Strategy s : List.of(Strategy.TRANSACTIONAL_OUTBOX, Strategy.OUTBOX_IDEMPOTENT)) {
            RunResult r = run("broker-outage", s);
            assertThat(r.fault().status()).isEqualTo(FaultExecution.Status.APPLIED);
            assertThat(r.outcome()).as(LabSupport.describe(r)).isEqualTo(Outcome.CONSISTENT);
            assertThat(r.measurements().publishAttempts()).as("failed attempts during the outage are visible")
                    .isGreaterThan(r.measurements().confirmedPublishes());
        }
    }

    private static RunResult run(String scenario, Strategy strategy) {
        RunResult r = LabSupport.run(scenario, strategy);
        System.out.println(LabSupport.describe(r));
        assertThat(r.obstacles()).as(LabSupport.describe(r)).isEmpty();
        return r;
    }

    private static void assertKilled(RunResult r) {
        assertThat(r.fault().status()).as(LabSupport.describe(r)).isEqualTo(FaultExecution.Status.APPLIED);
        assertThat(r.fault().verification()).contains("exit code 137");
        assertThat(r.timeline()).extracting(TimelineEvent::kind)
                .containsSubsequence("checkpoint-reached", "fault-applied", "recovered");
    }

    private static InvariantResult invariant(RunResult r, InvariantId id) {
        return r.invariants().stream().filter(i -> i.id() == id).findFirst().orElseThrow();
    }

    private static long confirmedPublishes(RunResult r, String eventId) {
        return r.timeline().stream().filter(e -> "publish".equals(e.kind())
                && eventId.equals(e.attributes().get("eventId")) && e.message().contains("CONFIRMED")).count();
    }
}
