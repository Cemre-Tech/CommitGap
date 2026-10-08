package com.example.commitgap.core.invariant;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.result.Measurements;
import com.example.commitgap.core.scenario.Workload;
import com.example.commitgap.core.snapshot.BrokerSnapshot;
import com.example.commitgap.core.snapshot.ConsumerSnapshot;
import com.example.commitgap.core.snapshot.LabSnapshot;
import com.example.commitgap.core.snapshot.OrderRow;
import com.example.commitgap.core.snapshot.OutboxRow;
import com.example.commitgap.core.snapshot.ProcessedMessage;
import com.example.commitgap.core.snapshot.ProducerSnapshot;
import com.example.commitgap.core.snapshot.StockMovement;
import com.example.commitgap.core.workload.PlannedOrder;
import com.example.commitgap.core.workload.WorkloadPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvariantEvaluatorTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    /** 10 orders, every 5th rolled back: orders 5 and 10 never commit. */
    private final WorkloadPlan plan = WorkloadPlan.from(new Workload(7, 10, 2, 100, 5, 1));

    @Test
    void healthyRunPassesEveryCheck() {
        Lab lab = Lab.healthy(plan);

        var results = evaluate(lab, Strategy.OUTBOX_IDEMPOTENT);

        assertThat(results).allSatisfy(r -> assertThat(r.status()).as(r.id().id()).isEqualTo(InvariantStatus.PASS));
    }

    @Test
    void aMissingAndADuplicateEventCannotHideEachOther() {
        Lab lab = Lab.healthy(plan);
        PlannedOrder lost = lab.committed().get(0);
        PlannedOrder twice = lab.committed().get(1);
        lab.removeEffect(lost);
        lab.addEffect(twice);
        // Row counts and stock are unchanged: one effect fewer, one effect more.
        assertThat(lab.movements).hasSize(lab.committed().size());
        assertThat(lab.stock).isEqualTo(100 - 2 * lab.committed().size());

        var results = evaluate(lab, Strategy.TRANSACTIONAL_OUTBOX);

        InvariantResult committed = find(results, InvariantId.COMMITTED_ORDER_HAS_BUSINESS_EFFECT);
        assertThat(committed.status()).isEqualTo(InvariantStatus.FAIL);
        assertThat(committed.evidence().get("missingWithoutPendingRecord")).containsExactly(lost.eventId().toString());
        InvariantResult once = find(results, InvariantId.STOCK_CHANGED_ONCE_PER_EVENT);
        assertThat(once.status()).isEqualTo(InvariantStatus.FAIL);
        assertThat(once.evidence().get("duplicatedEventIds")).containsExactly(twice.eventId() + " x2");
        assertThat(find(results, InvariantId.STOCK_MATCHES_COMMITTED_ORDERS).status())
                .as("the purely numeric check is fooled, which is why it is not the only check")
                .isEqualTo(InvariantStatus.PASS);
    }

    @Test
    void missingEffectWithPendingOutboxRowIsInconclusiveNotLoss() {
        Lab lab = Lab.healthy(plan);
        PlannedOrder late = lab.committed().get(3);
        lab.removeEffect(late);
        lab.markPending(late);

        var results = evaluate(lab, Strategy.TRANSACTIONAL_OUTBOX);

        assertThat(find(results, InvariantId.COMMITTED_ORDER_HAS_BUSINESS_EFFECT).status())
                .isEqualTo(InvariantStatus.INCONCLUSIVE);
        assertThat(find(results, InvariantId.STOCK_MATCHES_COMMITTED_ORDERS).status())
                .isEqualTo(InvariantStatus.INCONCLUSIVE);
        assertThat(find(results, InvariantId.NO_PENDING_WORK_AFTER_RECOVERY).status())
                .isEqualTo(InvariantStatus.INCONCLUSIVE);
    }

    @Test
    void missingEffectWithMessagesStillInTheBrokerIsInconclusive() {
        Lab lab = Lab.healthy(plan);
        lab.removeEffect(lab.committed().get(2));
        lab.broker = new BrokerSnapshot(true, 1, 0, 0, 9, 7, 0, null);

        var results = evaluate(lab, Strategy.NAIVE_DUAL_WRITE);

        assertThat(find(results, InvariantId.COMMITTED_ORDER_HAS_BUSINESS_EFFECT).status())
                .isEqualTo(InvariantStatus.INCONCLUSIVE);
    }

    @Test
    void missingEffectWithNothingPendingIsAViolation() {
        Lab lab = Lab.healthy(plan);
        lab.removeEffect(lab.committed().get(2));

        var results = evaluate(lab, Strategy.NAIVE_DUAL_WRITE);

        assertThat(find(results, InvariantId.COMMITTED_ORDER_HAS_BUSINESS_EFFECT).status())
                .isEqualTo(InvariantStatus.FAIL);
        assertThat(find(results, InvariantId.STOCK_MATCHES_COMMITTED_ORDERS).status())
                .isEqualTo(InvariantStatus.FAIL);
    }

    @Test
    void effectForARolledBackOrderIsAViolation() {
        Lab lab = Lab.healthy(plan);
        PlannedOrder rolledBack = plan.orders().get(4);
        assertThat(rolledBack.rollback()).isTrue();
        lab.addEffect(rolledBack);

        var results = evaluate(lab, Strategy.NAIVE_DUAL_WRITE);

        InvariantResult r = find(results, InvariantId.ROLLED_BACK_ORDER_HAS_NO_EFFECT);
        assertThat(r.status()).isEqualTo(InvariantStatus.FAIL);
        assertThat(r.evidence().get("effectWithoutCommittedOrder")).containsExactly(rolledBack.eventId().toString());
    }

    @Test
    void stockThatDisagreesWithTheLedgerIsAViolation() {
        Lab lab = Lab.healthy(plan);
        lab.stock -= 2;

        var results = evaluate(lab, Strategy.OUTBOX_IDEMPOTENT);

        InvariantResult r = find(results, InvariantId.STOCK_MATCHES_COMMITTED_ORDERS);
        assertThat(r.status()).isEqualTo(InvariantStatus.FAIL);
        assertThat(r.explanation()).contains("movement ledger");
    }

    @Test
    void dedupCheckDoesNotApplyToNonIdempotentConsumers() {
        var results = evaluate(Lab.healthy(plan), Strategy.TRANSACTIONAL_OUTBOX);

        assertThat(find(results, InvariantId.DEDUP_RECORD_MATCHES_BUSINESS_EFFECT).status())
                .isEqualTo(InvariantStatus.NOT_APPLICABLE);
    }

    @Test
    void dedupRecordWithoutBusinessEffectIsAViolation() {
        Lab lab = Lab.healthy(plan);
        PlannedOrder o = lab.committed().get(0);
        lab.removeEffect(o);
        lab.processed.add(new ProcessedMessage("stock-consumer", o.eventId(), T));

        var results = evaluate(lab, Strategy.OUTBOX_IDEMPOTENT);

        InvariantResult r = find(results, InvariantId.DEDUP_RECORD_MATCHES_BUSINESS_EFFECT);
        assertThat(r.status()).isEqualTo(InvariantStatus.FAIL);
        assertThat(r.evidence().get("processedWithoutEffect")).containsExactly(o.eventId().toString());
    }

    @Test
    void parkedOutboxRowsAndDeadLettersAreViolations() {
        Lab lab = Lab.healthy(plan);
        lab.outbox.replaceAll(r -> r.eventId().equals(lab.committed().get(0).eventId())
                ? new OutboxRow(r.eventId(), r.orderId(), "PARKED", 50, T, null) : r);

        var results = evaluate(lab, Strategy.TRANSACTIONAL_OUTBOX);

        assertThat(find(results, InvariantId.NO_PENDING_WORK_AFTER_RECOVERY).status())
                .isEqualTo(InvariantStatus.FAIL);
    }

    @Test
    void unmeasuredBrokerMakesPendingWorkUnknown() {
        Lab lab = Lab.healthy(plan);
        lab.broker = BrokerSnapshot.unavailable("connection refused");

        var results = evaluate(lab, Strategy.TRANSACTIONAL_OUTBOX);

        assertThat(find(results, InvariantId.NO_PENDING_WORK_AFTER_RECOVERY).status())
                .isEqualTo(InvariantStatus.INCONCLUSIVE);
    }

    @Test
    void measurementsKeepDeliveriesAttemptsAndEffectsApart() {
        Lab lab = Lab.healthy(plan);
        lab.addEffect(lab.committed().get(0));

        Measurements m = Measurements.of(plan, Strategy.TRANSACTIONAL_OUTBOX, lab.snapshot());

        assertThat(m.committedOrders()).isEqualTo(8);
        assertThat(m.uncommittedOrders()).isEqualTo(2);
        assertThat(m.businessEffects()).isEqualTo(9);
        assertThat(m.eventsWithEffect()).isEqualTo(8);
        assertThat(m.duplicateEffects()).isEqualTo(1);
        assertThat(m.expectedStock()).isEqualTo(100 - 16);
    }

    private List<InvariantResult> evaluate(Lab lab, Strategy strategy) {
        return new InvariantEvaluator(plan, strategy, lab.snapshot()).evaluate(List.of(InvariantId.values()));
    }

    private static InvariantResult find(List<InvariantResult> results, InvariantId id) {
        return results.stream().filter(r -> r.id() == id).findFirst().orElseThrow();
    }

    /** A hand-built, mutable model of the two databases and the broker. */
    private static final class Lab {
        final WorkloadPlan plan;
        final List<OrderRow> orders = new ArrayList<>();
        final List<OutboxRow> outbox = new ArrayList<>();
        final List<StockMovement> movements = new ArrayList<>();
        final List<ProcessedMessage> processed = new ArrayList<>();
        int stock;
        BrokerSnapshot broker = new BrokerSnapshot(true, 0, 0, 0, 8, 8, 0, null);
        long nextId = 1;

        private Lab(WorkloadPlan plan) {
            this.plan = plan;
            this.stock = plan.workload().initialStock();
        }

        static Lab healthy(WorkloadPlan plan) {
            Lab lab = new Lab(plan);
            for (PlannedOrder o : plan.orders()) {
                if (o.rollback()) {
                    continue;
                }
                lab.orders.add(new OrderRow(o.orderId(), o.eventId(), o.sku(), o.quantity(), T));
                lab.outbox.add(new OutboxRow(o.eventId(), o.orderId(), "SENT", 1, T, T));
                lab.addEffect(o);
                lab.processed.add(new ProcessedMessage("stock-consumer", o.eventId(), T));
            }
            return lab;
        }

        List<PlannedOrder> committed() {
            return plan.orders().stream().filter(o -> !o.rollback()).toList();
        }

        void addEffect(PlannedOrder o) {
            movements.add(new StockMovement(nextId++, o.eventId(), o.orderId(), o.sku(), -o.quantity(), T));
            stock -= o.quantity();
        }

        void removeEffect(PlannedOrder o) {
            int before = movements.size();
            movements.removeIf(m -> m.eventId().equals(o.eventId()));
            stock += (before - movements.size()) * o.quantity();
            processed.removeIf(p -> p.eventId().equals(o.eventId()));
        }

        void markPending(PlannedOrder o) {
            UUID e = o.eventId();
            outbox.replaceAll(r -> r.eventId().equals(e) ? new OutboxRow(e, r.orderId(), "PENDING", 3, T, null) : r);
        }

        LabSnapshot snapshot() {
            return new LabSnapshot(new ProducerSnapshot(orders, outbox, List.of()),
                    new ConsumerSnapshot(stock, movements, processed, List.of()), broker, T);
        }
    }
}
