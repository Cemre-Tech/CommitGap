package com.example.commitgap.core.result;

import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.snapshot.DeliveryRecord;
import com.example.commitgap.core.snapshot.LabSnapshot;
import com.example.commitgap.core.snapshot.OrderRow;
import com.example.commitgap.core.snapshot.OutboxRow;
import com.example.commitgap.core.snapshot.StockMovement;
import com.example.commitgap.core.workload.WorkloadPlan;
import java.util.UUID;

/**
 * Counters derived from the snapshot. Deliveries, consumer attempts and business effects are kept
 * apart on purpose: a redelivery is not a duplicate effect unless the ledger shows one.
 *
 * @param brokerPublished      messages published to the queue according to the broker's sampled statistics; -1 if not measured
 * @param brokerDeliveries     deliveries reported by the broker's sampled statistics (includes redeliveries); -1 if not measured
 * @param consumerAttempts     deliveries the consumer logged before processing
 * @param businessEffects      stock movement rows
 * @param duplicateEffects     business effects beyond the first for the same event id
 * @param outboxPending        -1 when the strategy has no outbox
 */
public record Measurements(
        int plannedOrders,
        int committedOrders,
        int uncommittedOrders,
        int publishAttempts,
        int confirmedPublishes,
        long brokerPublished,
        long brokerDeliveries,
        long brokerRedeliveries,
        int consumerAttempts,
        int consumerRedeliveries,
        int businessEffects,
        int eventsWithEffect,
        int duplicateEffects,
        int committedOrdersWithoutEffect,
        int outboxPending,
        int outboxParked,
        long queueReady,
        long queueUnacknowledged,
        long deadLettered,
        int initialStock,
        int expectedStock,
        int finalStock) {

    public static Measurements of(WorkloadPlan plan, Strategy strategy, LabSnapshot s) {
        var producer = s.producer();
        var consumer = s.consumer();
        var broker = s.broker();
        int committed = producer.orders().size();
        long distinctEvents = consumer.movements().stream().map(StockMovement::eventId).distinct().count();
        var effectEvents = consumer.movements().stream().map(StockMovement::eventId)
                .collect(java.util.stream.Collectors.toSet());
        int withoutEffect = (int) producer.orders().stream().map(OrderRow::eventId)
                .filter((UUID e) -> !effectEvents.contains(e)).count();
        int committedQuantity = producer.orders().stream().mapToInt(OrderRow::quantity).sum();
        return new Measurements(
                plan.orders().size(),
                committed,
                plan.orders().size() - committed,
                producer.publishAttempts().size(),
                (int) producer.publishAttempts().stream().filter(a -> "CONFIRMED".equals(a.result())).count(),
                broker.measured() ? broker.published() : -1,
                broker.measured() ? broker.deliveries() : -1,
                broker.measured() ? broker.redeliveries() : -1,
                consumer.deliveries().size(),
                (int) consumer.deliveries().stream().filter(DeliveryRecord::redelivered).count(),
                consumer.movements().size(),
                (int) distinctEvents,
                consumer.movements().size() - (int) distinctEvents,
                withoutEffect,
                strategy.usesOutboxRelay() ? (int) producer.outbox().stream().filter(OutboxRow::pending).count() : -1,
                strategy.usesOutboxRelay() ? (int) producer.outbox().stream().filter(OutboxRow::parked).count() : -1,
                broker.measured() ? broker.ready() : -1,
                broker.measured() ? broker.unacknowledged() : -1,
                broker.measured() ? broker.deadLettered() : -1,
                plan.workload().initialStock(),
                plan.workload().initialStock() - committedQuantity,
                consumer.stockQuantity());
    }
}
