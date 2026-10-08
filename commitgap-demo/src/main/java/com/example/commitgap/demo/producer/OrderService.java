package com.example.commitgap.demo.producer;

import com.example.commitgap.core.model.Checkpoint;
import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.demo.DemoProperties;
import com.example.commitgap.demo.control.CheckpointGate;
import com.example.commitgap.demo.events.EventPublisher;
import com.example.commitgap.demo.events.OrderCreatedEvent;
import com.example.commitgap.demo.events.PublishResult;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates orders. Transaction boundaries are explicit ({@link TransactionTemplate}) so it is visible
 * what commits together:
 * <ul>
 *   <li>naive-dual-write: the order commits alone; the producer publishes afterwards</li>
 *   <li>outbox strategies: the order and its outbox row commit in one transaction; the relay publishes</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "commitgap", name = "role", havingValue = "producer")
public class OrderService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CheckpointGate gate;
    private final EventPublisher publisher;
    private final Strategy strategy;

    public OrderService(JdbcTemplate jdbc, TransactionTemplate tx, CheckpointGate gate, EventPublisher publisher,
                        DemoProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.gate = gate;
        this.publisher = publisher;
        this.strategy = properties.strategyValue();
    }

    public record Result(boolean committed, boolean published, String publishResult, String detail) {
    }

    /** Thrown inside the transaction to roll it back deliberately, as a failed business validation would. */
    static final class DeliberateRollback extends RuntimeException {
        DeliberateRollback() {
            super("order rejected by the workload (deliberate rollback)", null, false, false);
        }
    }

    public Result create(OrderCreatedEvent event, boolean rollback) {
        String payload = publisher.toPayload(event);
        try {
            tx.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO orders (order_id, event_id, sku, quantity) VALUES (?, ?, ?, ?)",
                        event.orderId(), event.eventId(), event.sku(), event.quantity());
                if (strategy.usesOutboxRelay()) {
                    jdbc.update("INSERT INTO outbox (event_id, order_id, payload) VALUES (?, ?, ?)",
                            event.eventId(), event.orderId(), payload);
                }
                if (rollback) {
                    throw new DeliberateRollback();
                }
            });
        } catch (DeliberateRollback e) {
            return new Result(false, false, null, e.getMessage());
        }

        // executeWithoutResult returned normally: the commit has happened.
        gate.reach(Checkpoint.PRODUCER_AFTER_DB_COMMIT_BEFORE_PUBLISH,
                Map.of("eventId", event.eventId().toString(), "orderId", event.orderId().toString()));

        if (strategy.usesOutboxRelay()) {
            return new Result(true, false, null, "event committed to the outbox; the relay publishes it");
        }
        PublishResult published = publisher.publish(event.eventId(), event.orderId(), payload, "producer");
        return new Result(true, published.confirmed(), published.kind().name(), published.detail());
    }
}
