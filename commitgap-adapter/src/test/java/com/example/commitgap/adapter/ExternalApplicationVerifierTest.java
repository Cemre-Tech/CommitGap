package com.example.commitgap.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.commitgap.core.model.Outcome;
import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.scenario.Workload;
import com.example.commitgap.core.snapshot.BrokerSnapshot;
import com.example.commitgap.core.snapshot.ConsumerSnapshot;
import com.example.commitgap.core.snapshot.LabSnapshot;
import com.example.commitgap.core.snapshot.OrderRow;
import com.example.commitgap.core.snapshot.ProducerSnapshot;
import com.example.commitgap.core.snapshot.StockMovement;
import com.example.commitgap.core.workload.WorkloadPlan;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalApplicationVerifierTest {
    private static final WorkloadPlan PLAN = WorkloadPlan.from(new Workload(42, 1, 2, 10, 0, 1));
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void consistentApplicationPassesAndLostEffectFailsWithEventIdentity() {
        var safe = verify(snapshot(1, true, false), true, true);
        assertThat(safe.classification().outcome()).isEqualTo(Outcome.CONSISTENT);
        var loss = verify(snapshot(0, true, false), true, true);
        assertThat(loss.classification().outcome()).isEqualTo(Outcome.VIOLATION_OBSERVED);
        assertThat(loss.invariants().stream().flatMap(i -> i.evidence().values().stream()).toList())
                .anySatisfy(values -> assertThat(values).contains(PLAN.orders().getFirst().eventId().toString()));
    }

    @Test
    void duplicateEffectsFailEvenWhenTransportIsUnavailable() {
        var result = verify(snapshot(2, true, true), true, true);
        assertThat(result.classification().outcome()).isEqualTo(Outcome.VIOLATION_OBSERVED);
        assertThat(result.measurements().duplicateEffects()).isEqualTo(1);
    }

    @Test
    void emptyOrPartialWorkloadCannotPassVacuously() {
        var result = verify(snapshot(0, false, false), true, true);
        assertThat(result.classification().outcome()).isEqualTo(Outcome.INCONCLUSIVE);
        assertThat(result.classification().reasons()).anyMatch(r -> r.contains("absent"));
    }

    @Test
    void movingSnapshotOrIncompleteDriverMakesMeasurementInconclusive() {
        assertThat(verify(snapshot(1, true, false), true, false).classification().outcome())
                .isEqualTo(Outcome.INCONCLUSIVE);
        assertThat(verify(snapshot(1, true, false), false, true).classification().outcome())
                .isEqualTo(Outcome.INCONCLUSIVE);
    }

    @Test
    void readFailureDoesNotLeakCredentialsOrInventEmptyData() {
        var result = ExternalApplicationVerifier.verify(PLAN, Strategy.NAIVE_DUAL_WRITE,
                plan -> { throw new IOException("password=do-not-publish"); });
        assertThat(result.classification().outcome()).isEqualTo(Outcome.INCONCLUSIVE);
        assertThat(result.classification().reasons().toString()).contains("IOException").doesNotContain("do-not-publish");
        assertThat(result.invariants()).isEmpty();
        assertThat(result.measurements()).isNull();
    }

    @Test
    void unknownBrokerBacklogNeverTurnsMissingWorkIntoProvenLoss() {
        var result = verify(snapshot(0, true, true), true, true);
        assertThat(result.classification().outcome()).isEqualTo(Outcome.INCONCLUSIVE);
    }

    private static ExternalApplicationVerifier.Verification verify(LabSnapshot snapshot, boolean completed,
                                                                   boolean quiescent) {
        return ExternalApplicationVerifier.verify(PLAN, Strategy.NAIVE_DUAL_WRITE,
                plan -> new ExternalObservation(snapshot, completed, quiescent, List.of()));
    }

    private static LabSnapshot snapshot(int effects, boolean committed, boolean unknownBroker) {
        var order = PLAN.orders().getFirst();
        var orders = committed ? List.of(new OrderRow(order.orderId(), order.eventId(), order.sku(), order.quantity(), NOW))
                : List.<OrderRow>of();
        var movements = java.util.stream.IntStream.range(0, effects)
                .mapToObj(n -> new StockMovement(n + 1, order.eventId(), order.orderId(), order.sku(), -order.quantity(), NOW))
                .toList();
        return new LabSnapshot(new ProducerSnapshot(orders, List.of(), List.of()),
                new ConsumerSnapshot(10 - effects * 2, movements, List.of(), List.of()),
                unknownBroker ? BrokerSnapshot.unavailable("not measured")
                        : new BrokerSnapshot(true, 0, 0, 0, effects, effects, 0, null), NOW);
    }
}
