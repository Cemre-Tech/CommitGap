package com.example.commitgap.core.invariant;

import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.snapshot.LabSnapshot;
import com.example.commitgap.core.snapshot.OrderRow;
import com.example.commitgap.core.snapshot.OutboxRow;
import com.example.commitgap.core.snapshot.ProcessedMessage;
import com.example.commitgap.core.snapshot.StockMovement;
import com.example.commitgap.core.workload.PlannedOrder;
import com.example.commitgap.core.workload.WorkloadPlan;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Evaluates invariants against database snapshots. Every check works on identity sets (event ids,
 * order ids) rather than row counts, so one missing event and one extra event cannot cancel out.
 *
 * <p>Missing work is only reported as a violation when nothing that could still deliver it is
 * known: no pending outbox row for the event and no message left in the broker. Otherwise the result
 * is {@link InvariantStatus#INCONCLUSIVE}; a timeout is not treated as permanent loss.
 */
public final class InvariantEvaluator {

    private final WorkloadPlan plan;
    private final Strategy strategy;
    private final LabSnapshot snapshot;

    private final Map<UUID, OrderRow> committedByEvent = new LinkedHashMap<>();
    private final Map<UUID, List<StockMovement>> movementsByEvent = new LinkedHashMap<>();
    private final Set<UUID> pendingOutboxEvents;
    private final Set<UUID> parkedOutboxEvents;

    public InvariantEvaluator(WorkloadPlan plan, Strategy strategy, LabSnapshot snapshot) {
        this.plan = plan;
        this.strategy = strategy;
        this.snapshot = snapshot;
        for (OrderRow order : snapshot.producer().orders()) {
            committedByEvent.put(order.eventId(), order);
        }
        for (StockMovement m : snapshot.consumer().movements()) {
            movementsByEvent.computeIfAbsent(m.eventId(), k -> new ArrayList<>()).add(m);
        }
        pendingOutboxEvents = snapshot.producer().outbox().stream().filter(OutboxRow::pending)
                .map(OutboxRow::eventId).collect(Collectors.toSet());
        parkedOutboxEvents = snapshot.producer().outbox().stream().filter(OutboxRow::parked)
                .map(OutboxRow::eventId).collect(Collectors.toSet());
    }

    public List<InvariantResult> evaluate(Collection<InvariantId> ids) {
        return ids.stream().map(this::evaluate).toList();
    }

    public InvariantResult evaluate(InvariantId id) {
        return switch (id) {
            case COMMITTED_ORDER_HAS_BUSINESS_EFFECT -> committedOrderHasBusinessEffect();
            case STOCK_CHANGED_ONCE_PER_EVENT -> stockChangedOncePerEvent();
            case ROLLED_BACK_ORDER_HAS_NO_EFFECT -> rolledBackOrderHasNoEffect();
            case STOCK_MATCHES_COMMITTED_ORDERS -> stockMatchesCommittedOrders();
            case DEDUP_RECORD_MATCHES_BUSINESS_EFFECT -> dedupRecordMatchesBusinessEffect();
            case NO_PENDING_WORK_AFTER_RECOVERY -> noPendingWorkAfterRecovery();
        };
    }

    private boolean brokerMayStillDeliver() {
        return !snapshot.broker().measured() || snapshot.broker().backlog() > 0;
    }

    private InvariantResult committedOrderHasBusinessEffect() {
        InvariantId id = InvariantId.COMMITTED_ORDER_HAS_BUSINESS_EFFECT;
        Set<String> foreignOrders = new TreeSet<>();
        Set<String> eventMismatch = new TreeSet<>();
        Set<String> missing = new TreeSet<>();
        Set<String> missingButPending = new TreeSet<>();
        Set<String> missingUnexplained = new TreeSet<>();
        for (OrderRow order : snapshot.producer().orders()) {
            PlannedOrder planned = plan.byOrderId(order.orderId()).orElse(null);
            if (planned == null) {
                foreignOrders.add(order.orderId().toString());
                continue;
            }
            if (!planned.eventId().equals(order.eventId())) {
                eventMismatch.add(order.orderId() + " stored event " + order.eventId() + ", planned " + planned.eventId());
            }
            List<StockMovement> effects = movementsByEvent.getOrDefault(planned.eventId(), List.of());
            boolean hasEffectForThisOrder = effects.stream().anyMatch(m -> m.orderId().equals(order.orderId()));
            if (!hasEffectForThisOrder) {
                String eventId = planned.eventId().toString();
                missing.add(eventId);
                if (pendingOutboxEvents.contains(planned.eventId())) {
                    missingButPending.add(eventId);
                } else {
                    missingUnexplained.add(eventId);
                }
            }
        }
        int committed = snapshot.producer().orders().size();
        Map<String, List<String>> evidence = evidence(
                "missingEffectEventIds", missing,
                "missingWithPendingOutboxRow", missingButPending,
                "missingWithoutPendingRecord", missingUnexplained,
                "ordersNotInWorkload", foreignOrders,
                "eventIdMismatches", eventMismatch);
        String expected = committed + " committed orders, each with an effect for its own event id";
        String actual = (committed - missing.size()) + " with effect, " + missing.size() + " without";

        if (!foreignOrders.isEmpty() || !eventMismatch.isEmpty()) {
            return new InvariantResult(id, InvariantStatus.FAIL, expected, actual,
                    "The producer database holds orders or event ids that do not match the workload plan.", evidence);
        }
        if (missing.isEmpty()) {
            return new InvariantResult(id, InvariantStatus.PASS, expected, actual,
                    "Every committed order has a stock movement for its event id.", evidence);
        }
        if (missingUnexplained.isEmpty() || brokerMayStillDeliver()) {
            return new InvariantResult(id, InvariantStatus.INCONCLUSIVE, expected, actual,
                    "Some committed orders have no effect yet, but pending outbox rows or broker messages could "
                            + "still deliver them. This is not counted as loss.", evidence);
        }
        return new InvariantResult(id, InvariantStatus.FAIL, expected, actual,
                missingUnexplained.size() + " committed order(s) have no business effect, no pending outbox row and "
                        + "no message left in the broker within the observation window.", evidence);
    }

    private InvariantResult stockChangedOncePerEvent() {
        InvariantId id = InvariantId.STOCK_CHANGED_ONCE_PER_EVENT;
        Set<String> duplicated = new TreeSet<>();
        Set<String> wrongAmount = new TreeSet<>();
        Set<String> wrongOrder = new TreeSet<>();
        int extraEffects = 0;
        for (Map.Entry<UUID, List<StockMovement>> e : movementsByEvent.entrySet()) {
            List<StockMovement> movements = e.getValue();
            if (movements.size() > 1) {
                duplicated.add(e.getKey() + " x" + movements.size());
                extraEffects += movements.size() - 1;
            }
            PlannedOrder planned = plan.byEventId(e.getKey()).orElse(null);
            if (planned == null) {
                continue; // reported by rolled-back-order-has-no-effect
            }
            for (StockMovement m : movements) {
                if (m.delta() != -planned.quantity()) {
                    wrongAmount.add(e.getKey() + " delta " + m.delta() + ", expected " + (-planned.quantity()));
                }
                if (!m.orderId().equals(planned.orderId())) {
                    wrongOrder.add(e.getKey() + " carried order " + m.orderId() + ", expected " + planned.orderId());
                }
            }
        }
        Map<String, List<String>> evidence = evidence(
                "duplicatedEventIds", duplicated,
                "wrongQuantity", wrongAmount,
                "wrongOrderForEvent", wrongOrder);
        String expected = "1 movement per event, delta = -quantity, matching order id";
        String actual = movementsByEvent.size() + " events with effects, " + extraEffects + " extra effect(s)";
        if (duplicated.isEmpty() && wrongAmount.isEmpty() && wrongOrder.isEmpty()) {
            return new InvariantResult(id, InvariantStatus.PASS, expected, actual,
                    "No event changed stock more than once or by the wrong amount.", evidence);
        }
        return new InvariantResult(id, InvariantStatus.FAIL, expected, actual,
                duplicated.isEmpty()
                        ? "Some effects have the wrong quantity or order."
                        : duplicated.size() + " event(s) changed stock more than once.",
                evidence);
    }

    private InvariantResult rolledBackOrderHasNoEffect() {
        InvariantId id = InvariantId.ROLLED_BACK_ORDER_HAS_NO_EFFECT;
        Set<UUID> committedOrderIds = snapshot.producer().orders().stream().map(OrderRow::orderId)
                .collect(Collectors.toSet());
        Set<String> uncommittedWithEffect = new TreeSet<>();
        Set<String> unknownEvents = new TreeSet<>();
        Set<String> plannedRollbackCommitted = new TreeSet<>();
        int notCommitted = 0;
        for (PlannedOrder o : plan.orders()) {
            boolean committed = committedOrderIds.contains(o.orderId());
            if (!committed) {
                notCommitted++;
                if (movementsByEvent.containsKey(o.eventId())) {
                    uncommittedWithEffect.add(o.eventId().toString());
                }
            } else if (o.rollback()) {
                plannedRollbackCommitted.add(o.orderId().toString());
            }
        }
        for (UUID eventId : movementsByEvent.keySet()) {
            if (plan.byEventId(eventId).isEmpty()) {
                unknownEvents.add(eventId.toString());
            }
        }
        Map<String, List<String>> evidence = evidence(
                "effectWithoutCommittedOrder", uncommittedWithEffect,
                "unknownEventIds", unknownEvents,
                "plannedRollbackButCommitted", plannedRollbackCommitted);
        String expected = "0 effects for " + notCommitted + " uncommitted order(s) and for unknown events";
        String actual = (uncommittedWithEffect.size() + unknownEvents.size()) + " such effect(s)";
        if (!plannedRollbackCommitted.isEmpty()) {
            return new InvariantResult(id, InvariantStatus.INCONCLUSIVE, expected, actual,
                    "Orders the workload asked to roll back were committed, so the rollback path was not exercised.",
                    evidence);
        }
        if (uncommittedWithEffect.isEmpty() && unknownEvents.isEmpty()) {
            return new InvariantResult(id, InvariantStatus.PASS, expected, actual,
                    notCommitted == 0
                            ? "No order was rolled back in this workload, and no unknown event changed stock."
                            : "No rolled-back order and no unknown event changed stock.",
                    evidence);
        }
        return new InvariantResult(id, InvariantStatus.FAIL, expected, actual,
                "Stock changed for events whose order never committed.", evidence);
    }

    private InvariantResult stockMatchesCommittedOrders() {
        InvariantId id = InvariantId.STOCK_MATCHES_COMMITTED_ORDERS;
        int initial = plan.workload().initialStock();
        int committedQuantity = snapshot.producer().orders().stream().mapToInt(OrderRow::quantity).sum();
        int expectedStock = initial - committedQuantity;
        int ledgerStock = initial + snapshot.consumer().movements().stream().mapToInt(StockMovement::delta).sum();
        int actualStock = snapshot.consumer().stockQuantity();
        Map<String, List<String>> evidence = Map.of(
                "values", List.of("initial=" + initial, "committedQuantity=" + committedQuantity,
                        "expected=" + expectedStock, "actual=" + actualStock, "ledger=" + ledgerStock));
        String expected = String.valueOf(expectedStock);
        String actual = String.valueOf(actualStock);
        if (actualStock != ledgerStock) {
            return new InvariantResult(id, InvariantStatus.FAIL, expected, actual,
                    "The stock row (" + actualStock + ") disagrees with the movement ledger (" + ledgerStock
                            + "): a stock change and its movement did not commit together.", evidence);
        }
        if (actualStock == expectedStock) {
            return new InvariantResult(id, InvariantStatus.PASS, expected, actual,
                    "Stock matches the committed orders. This numeric check alone could hide a missing and a "
                            + "duplicate event; the identity checks above cover that.", evidence);
        }
        if (actualStock > expectedStock && (!pendingOutboxEvents.isEmpty() || brokerMayStillDeliver())) {
            return new InvariantResult(id, InvariantStatus.INCONCLUSIVE, expected, actual,
                    "Stock is higher than expected while work is still pending.", evidence);
        }
        return new InvariantResult(id, InvariantStatus.FAIL, expected, actual,
                "Stock differs from initial stock minus committed quantities by " + (actualStock - expectedStock) + ".",
                evidence);
    }

    private InvariantResult dedupRecordMatchesBusinessEffect() {
        InvariantId id = InvariantId.DEDUP_RECORD_MATCHES_BUSINESS_EFFECT;
        if (!strategy.idempotentConsumer()) {
            return new InvariantResult(id, InvariantStatus.NOT_APPLICABLE, "-", "-",
                    "The " + strategy.id() + " consumer keeps no deduplication records.", Map.of());
        }
        Set<UUID> processed = snapshot.consumer().processed().stream().map(ProcessedMessage::eventId)
                .collect(Collectors.toSet());
        Set<String> processedWithoutEffect = new TreeSet<>();
        Set<String> effectWithoutRecord = new TreeSet<>();
        for (UUID eventId : processed) {
            if (!movementsByEvent.containsKey(eventId)) {
                processedWithoutEffect.add(eventId.toString());
            }
        }
        for (UUID eventId : movementsByEvent.keySet()) {
            if (!processed.contains(eventId)) {
                effectWithoutRecord.add(eventId.toString());
            }
        }
        Map<String, List<String>> evidence = evidence(
                "processedWithoutEffect", processedWithoutEffect,
                "effectWithoutProcessedRecord", effectWithoutRecord);
        String expected = "processed-message ids = movement event ids";
        String actual = processed.size() + " processed records, " + movementsByEvent.size() + " events with effects";
        if (processedWithoutEffect.isEmpty() && effectWithoutRecord.isEmpty()) {
            return new InvariantResult(id, InvariantStatus.PASS, expected, actual,
                    "Deduplication records and business effects committed together.", evidence);
        }
        return new InvariantResult(id, InvariantStatus.FAIL, expected, actual,
                "Deduplication records and business effects diverged.", evidence);
    }

    private InvariantResult noPendingWorkAfterRecovery() {
        InvariantId id = InvariantId.NO_PENDING_WORK_AFTER_RECOVERY;
        var broker = snapshot.broker();
        Map<String, List<String>> evidence = evidence(
                "pendingOutboxEventIds", sorted(pendingOutboxEvents),
                "parkedOutboxEventIds", sorted(parkedOutboxEvents));
        String expected = "0 pending/parked outbox rows, 0 queued, 0 unacked, 0 dead-lettered";
        if (!broker.measured()) {
            return new InvariantResult(id, InvariantStatus.INCONCLUSIVE, expected,
                    "broker not measured: " + broker.error(),
                    "The broker could not be queried, so pending work is unknown.", evidence);
        }
        String actual = pendingOutboxEvents.size() + " pending, " + parkedOutboxEvents.size() + " parked, "
                + broker.ready() + " queued, " + broker.unacknowledged() + " unacked, "
                + broker.deadLettered() + " dead-lettered";
        if (!parkedOutboxEvents.isEmpty() || broker.deadLettered() > 0) {
            return new InvariantResult(id, InvariantStatus.FAIL, expected, actual,
                    "Work was given up: outbox rows were parked or messages were dead-lettered.", evidence);
        }
        if (!pendingOutboxEvents.isEmpty() || broker.backlog() > 0) {
            return new InvariantResult(id, InvariantStatus.INCONCLUSIVE, expected, actual,
                    "Work was still pending when the observation window closed.", evidence);
        }
        return new InvariantResult(id, InvariantStatus.PASS, expected, actual,
                "Nothing was pending in the outbox or the broker.", evidence);
    }

    private static Set<String> sorted(Set<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.toCollection(TreeSet::new));
    }

    /** Builds an evidence map from (name, Set&lt;String&gt;) pairs, leaving out empty sets. */
    private static Map<String, List<String>> evidence(Object... pairs) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            Set<String> values = (Set<String>) pairs[i + 1];
            if (!values.isEmpty()) {
                result.put((String) pairs[i], List.copyOf(values));
            }
        }
        return result;
    }
}
