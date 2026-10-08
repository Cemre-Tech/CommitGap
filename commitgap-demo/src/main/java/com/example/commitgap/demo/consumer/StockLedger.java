package com.example.commitgap.demo.consumer;

import com.example.commitgap.demo.events.OrderCreatedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The consumer's business transaction: decrement stock and record the movement.
 *
 * <p>The idempotent variant first inserts {@code (consumer_name, event_id)} into
 * {@code processed_message} with {@code ON CONFLICT DO NOTHING}, in the same transaction. There is no
 * check-then-act: when two workers handle the same event concurrently, the second insert waits on the
 * primary key until the first transaction finishes, then inserts nothing and the second worker skips
 * the business change. If the first transaction rolls back, its dedup row disappears with it and the
 * second worker applies the event, so a failed transaction never counts a message as processed.
 *
 * <p>The non-idempotent variant applies every delivery. Nothing in the business tables deduplicates
 * for it: {@code stock_movement.event_id} is deliberately not unique.
 */
public class StockLedger {

    public enum Outcome { APPLIED, DUPLICATE_SKIPPED }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final boolean idempotent;
    private final String consumerName;
    private volatile Runnable afterDedupInsert = () -> { };

    public StockLedger(JdbcTemplate jdbc, TransactionTemplate tx, boolean idempotent, String consumerName) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.idempotent = idempotent;
        this.consumerName = consumerName;
    }

    public Outcome apply(OrderCreatedEvent event) {
        return tx.execute(status -> {
            if (idempotent) {
                int inserted = jdbc.update(
                        "INSERT INTO processed_message (consumer_name, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                        consumerName, event.eventId());
                if (inserted == 0) {
                    return Outcome.DUPLICATE_SKIPPED;
                }
                afterDedupInsert.run();
            }
            int updated = jdbc.update("UPDATE stock SET quantity = quantity - ? WHERE sku = ?",
                    event.quantity(), event.sku());
            if (updated != 1) {
                throw new IllegalStateException("no stock row for sku " + event.sku());
            }
            jdbc.update("INSERT INTO stock_movement (event_id, order_id, sku, delta) VALUES (?, ?, ?, ?)",
                    event.eventId(), event.orderId(), event.sku(), -event.quantity());
            return Outcome.APPLIED;
        });
    }

    public void seedStock(String sku, int quantity) {
        jdbc.update("INSERT INTO stock (sku, quantity) VALUES (?, ?) ON CONFLICT (sku) DO NOTHING", sku, quantity);
    }

    /** Test hook: runs inside the transaction right after a successful dedup insert. */
    void afterDedupInsert(Runnable hook) {
        this.afterDedupInsert = hook;
    }
}
