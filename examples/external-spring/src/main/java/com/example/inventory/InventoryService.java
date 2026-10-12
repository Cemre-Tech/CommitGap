package com.example.inventory;

import com.example.commitgap.adapter.CheckpointSignal;
import com.example.commitgap.adapter.ExternalApplicationAdapter;
import com.example.commitgap.adapter.ExternalObservation;
import com.example.commitgap.core.model.Checkpoint;
import com.example.commitgap.core.model.Strategy;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application-owned schema and workflow. H2 persists the actual business transactions; the local
 * transport is a queue model, not RabbitMQ. Every SQL read/write is scoped by a fresh run identifier.
 */
public final class InventoryService implements ExternalApplicationAdapter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    private final UUID runId = UUID.randomUUID();
    private final Strategy strategy;
    private final ConcurrentLinkedQueue<PlannedOrder> transport = new ConcurrentLinkedQueue<>();
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final AtomicLong activityGeneration = new AtomicLong();
    private volatile boolean workloadCompleted;
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong redeliveries = new AtomicLong();

    public InventoryService(JdbcTemplate jdbc, PlatformTransactionManager manager, Strategy strategy, WorkloadPlan plan) {
        this.jdbc = jdbc;
        this.strategy = strategy;
        writes = new TransactionTemplate(manager);
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        initializeOwnedSchema();
        jdbc.update("INSERT INTO inventory(run_id, sku, quantity) VALUES (?, ?, ?)",
                runId, WorkloadPlan.SKU, plan.workload().initialStock());
    }

    /** Checkpoint is outside executeWithoutResult: its transaction has really committed. */
    public void produce(PlannedOrder order, CheckpointSignal checkpoint) throws InterruptedException, TimeoutException {
        beginActivity();
        try {
            writes.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO purchase_order(run_id, order_id, event_id, sku, quantity, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                        runId, order.orderId(), order.eventId(), order.sku(), order.quantity());
                if (strategy.usesOutboxRelay()) {
                    jdbc.update("INSERT INTO event_outbox(run_id, event_id, order_id, status, created_at) "
                            + "VALUES (?, ?, ?, 'PENDING', CURRENT_TIMESTAMP)", runId, order.eventId(), order.orderId());
                }
                if (order.rollback()) {
                    status.setRollbackOnly();
                }
            });
            if (!order.rollback() && !strategy.usesOutboxRelay()) {
                checkpoint.hit(Checkpoint.PRODUCER_AFTER_DB_COMMIT_BEFORE_PUBLISH, order.eventId());
                publish(order);
            }
        } finally {
            endActivity();
        }
    }

    /** Simple application-owned relay, used after the producer has completed. */
    public void relay(WorkloadPlan plan) {
        beginActivity();
        try {
            for (var order : plan.orders()) {
                Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox "
                        + "WHERE run_id=? AND event_id=? AND status='PENDING'", Integer.class, runId, order.eventId());
                if (pending != null && pending > 0) {
                    publish(order);
                    jdbc.update("UPDATE event_outbox SET status='SENT', sent_at=CURRENT_TIMESTAMP "
                            + "WHERE run_id=? AND event_id=?", runId, order.eventId());
                }
            }
        } finally {
            endActivity();
        }
    }

    private void publish(PlannedOrder order) {
        transport.add(order);
        published.incrementAndGet();
    }

    /** An aborted post-commit consumer leaves the envelope queued for the caller's redelivery. */
    public void consume(CheckpointSignal checkpoint, boolean redelivery) throws InterruptedException, TimeoutException {
        beginActivity();
        try {
            PlannedOrder order = transport.peek();
            if (order == null) {
                return;
            }
            delivered.incrementAndGet();
            if (redelivery) {
                redeliveries.incrementAndGet();
            }
            writes.executeWithoutResult(status -> {
                // Lock before the dedup lookup so concurrent consumers serialize for this inventory.
                jdbc.queryForObject("SELECT quantity FROM inventory WHERE run_id=? AND sku=? FOR UPDATE",
                        Integer.class, runId, order.sku());
                if (strategy.idempotentConsumer()) {
                    Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE run_id=? AND event_id=?",
                            Integer.class, runId, order.eventId());
                    if (exists != null && exists > 0) {
                        return;
                    }
                    jdbc.update("INSERT INTO processed_event(run_id, event_id, processed_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                            runId, order.eventId());
                }
                jdbc.update("UPDATE inventory SET quantity=quantity-? WHERE run_id=? AND sku=?",
                        order.quantity(), runId, order.sku());
                jdbc.update("INSERT INTO inventory_effect(run_id, event_id, order_id, sku, delta, applied_at) "
                        + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                        runId, order.eventId(), order.orderId(), order.sku(), -order.quantity());
            });
            checkpoint.hit(Checkpoint.CONSUMER_AFTER_BUSINESS_COMMIT_BEFORE_ACK, order.eventId());
            transport.remove(order); // local transport acknowledgement
        } finally {
            endActivity();
        }
    }

    /** Driver calls this only after all workload requests and recovery workers have finished. */
    public void workloadCompleted() {
        beginActivity();
        try {
            workloadCompleted = true;
        } finally {
            endActivity();
        }
    }

    @Override
    public ExternalObservation capture(WorkloadPlan plan) {
        long generationBefore = activityGeneration.get();
        boolean initiallyIdle = activeWorkers.get() == 0;
        return reads.execute(status -> {
            var orders = jdbc.query("SELECT * FROM purchase_order WHERE run_id=? ORDER BY created_at, order_id",
                    (rs, n) -> new OrderRow(rs.getObject("order_id", UUID.class), rs.getObject("event_id", UUID.class),
                            rs.getString("sku"), rs.getInt("quantity"), rs.getTimestamp("created_at").toInstant()), runId);
            var outbox = jdbc.query("SELECT * FROM event_outbox WHERE run_id=? ORDER BY event_id",
                    (rs, n) -> new OutboxRow(rs.getObject("event_id", UUID.class), rs.getObject("order_id", UUID.class),
                            rs.getString("status"), 0, rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant()), runId);
            var movements = jdbc.query("SELECT * FROM inventory_effect WHERE run_id=? ORDER BY id",
                    (rs, n) -> new StockMovement(rs.getLong("id"), rs.getObject("event_id", UUID.class),
                            rs.getObject("order_id", UUID.class), rs.getString("sku"), rs.getInt("delta"),
                            rs.getTimestamp("applied_at").toInstant()), runId);
            var processed = jdbc.query("SELECT * FROM processed_event WHERE run_id=? ORDER BY event_id",
                    (rs, n) -> new ProcessedMessage("inventory", rs.getObject("event_id", UUID.class),
                            rs.getTimestamp("processed_at").toInstant()), runId);
            int stock = jdbc.queryForObject("SELECT quantity FROM inventory WHERE run_id=? AND sku=?",
                    Integer.class, runId, WorkloadPlan.SKU);
            var snapshot = new LabSnapshot(new ProducerSnapshot(orders, outbox, List.of()),
                    new ConsumerSnapshot(stock, movements, processed, List.of()),
                    new BrokerSnapshot(true, transport.size(), 0, 0, published.get(), delivered.get(), redeliveries.get(), null),
                    Instant.now());
            boolean completed = workloadCompleted;
            // A worker can both start and finish after the initial idle check. Comparing generations
            // after every database, transport, and status read catches that whole overlap, too.
            boolean quiescent = initiallyIdle && activeWorkers.get() == 0
                    && activityGeneration.get() == generationBefore;
            return new ExternalObservation(snapshot, completed, quiescent, List.of());
        });
    }

    private void beginActivity() {
        activityGeneration.incrementAndGet();
        activeWorkers.incrementAndGet();
    }

    private void endActivity() {
        // Publish activity before advertising idleness; otherwise a finished worker could be missed.
        activityGeneration.incrementAndGet();
        activeWorkers.decrementAndGet();
    }

    // This is the sample application's schema, never a schema supplied by the verifier.
    private void initializeOwnedSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS inventory (run_id UUID, sku VARCHAR(100), quantity INT NOT NULL, "
                + "PRIMARY KEY(run_id, sku))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS purchase_order (run_id UUID, order_id UUID, event_id UUID NOT NULL, "
                + "sku VARCHAR(100), quantity INT, created_at TIMESTAMP WITH TIME ZONE, PRIMARY KEY(run_id, order_id), "
                + "UNIQUE(run_id, event_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS event_outbox (run_id UUID, event_id UUID, order_id UUID, "
                + "status VARCHAR(20), created_at TIMESTAMP WITH TIME ZONE, sent_at TIMESTAMP WITH TIME ZONE, "
                + "PRIMARY KEY(run_id, event_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS inventory_effect (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                + "run_id UUID, event_id UUID, order_id UUID, sku VARCHAR(100), delta INT, applied_at TIMESTAMP WITH TIME ZONE)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS processed_event (run_id UUID, event_id UUID, "
                + "processed_at TIMESTAMP WITH TIME ZONE, PRIMARY KEY(run_id, event_id))");
    }
}
