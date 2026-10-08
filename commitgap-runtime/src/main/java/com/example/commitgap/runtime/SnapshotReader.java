package com.example.commitgap.runtime;

import com.example.commitgap.core.snapshot.ConsumerSnapshot;
import com.example.commitgap.core.snapshot.DeliveryRecord;
import com.example.commitgap.core.snapshot.OrderRow;
import com.example.commitgap.core.snapshot.OutboxRow;
import com.example.commitgap.core.snapshot.ProcessedMessage;
import com.example.commitgap.core.snapshot.ProducerSnapshot;
import com.example.commitgap.core.snapshot.PublishAttempt;
import com.example.commitgap.core.snapshot.StockMovement;
import com.example.commitgap.core.workload.WorkloadPlan;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads the producer and consumer databases of one lab with plain JDBC. These rows are the only
 * evidence used for business correctness.
 */
final class SnapshotReader {

    record Database(String url, String user, String password) {
    }

    /** Small, cheap state used by the observer to decide whether the system has settled. */
    record QuickState(int pendingOutbox, int movements, int deliveries, int publishAttempts) {
    }

    private final Database producer;
    private final Database consumer;

    SnapshotReader(Database producer, Database consumer) {
        this.producer = producer;
        this.consumer = consumer;
    }

    ProducerSnapshot producer() throws SQLException {
        try (Connection c = connect(producer)) {
            List<OrderRow> orders = query(c, "SELECT order_id, event_id, sku, quantity, created_at FROM orders ORDER BY created_at, order_id",
                    rs -> new OrderRow(uuid(rs, 1), uuid(rs, 2), rs.getString(3), rs.getInt(4), instant(rs, 5)));
            List<OutboxRow> outbox = query(c, "SELECT event_id, order_id, status, attempts, created_at, sent_at FROM outbox ORDER BY seq",
                    rs -> new OutboxRow(uuid(rs, 1), uuid(rs, 2), rs.getString(3), rs.getInt(4), instant(rs, 5), instant(rs, 6)));
            List<PublishAttempt> attempts = query(c, "SELECT event_id, publisher, result, detail, at FROM publish_log ORDER BY id",
                    rs -> new PublishAttempt(uuid(rs, 1), rs.getString(2), rs.getString(3), rs.getString(4), instant(rs, 5)));
            return new ProducerSnapshot(orders, outbox, attempts);
        }
    }

    ConsumerSnapshot consumer() throws SQLException {
        try (Connection c = connect(consumer)) {
            List<Integer> stock = query(c, "SELECT quantity FROM stock WHERE sku = '" + WorkloadPlan.SKU + "'", rs -> rs.getInt(1));
            if (stock.isEmpty()) {
                throw new SQLException("stock row " + WorkloadPlan.SKU + " is missing; the consumer never initialised its database");
            }
            List<StockMovement> movements = query(c, "SELECT id, event_id, order_id, sku, delta, applied_at FROM stock_movement ORDER BY id",
                    rs -> new StockMovement(rs.getLong(1), uuid(rs, 2), uuid(rs, 3), rs.getString(4), rs.getInt(5), instant(rs, 6)));
            List<ProcessedMessage> processed = query(c, "SELECT consumer_name, event_id, processed_at FROM processed_message ORDER BY processed_at",
                    rs -> new ProcessedMessage(rs.getString(1), uuid(rs, 2), instant(rs, 3)));
            List<DeliveryRecord> deliveries = query(c, "SELECT id, event_id, redelivered, worker, outcome, received_at FROM delivery_log ORDER BY id",
                    rs -> new DeliveryRecord(rs.getLong(1), uuid(rs, 2), rs.getBoolean(3), rs.getString(4), rs.getString(5), instant(rs, 6)));
            return new ConsumerSnapshot(stock.get(0), movements, processed, deliveries);
        }
    }

    QuickState quickState() throws SQLException {
        int pending;
        int attempts;
        try (Connection c = connect(producer)) {
            pending = count(c, "SELECT count(*) FROM outbox WHERE status = 'PENDING'");
            attempts = count(c, "SELECT count(*) FROM publish_log");
        }
        try (Connection c = connect(consumer)) {
            return new QuickState(pending,
                    count(c, "SELECT count(*) FROM stock_movement"),
                    count(c, "SELECT count(*) FROM delivery_log"),
                    attempts);
        }
    }

    int failedPublishAttempts() throws SQLException {
        try (Connection c = connect(producer)) {
            return count(c, "SELECT count(*) FROM publish_log WHERE result <> 'CONFIRMED'");
        }
    }

    int pendingOutbox() throws SQLException {
        try (Connection c = connect(producer)) {
            return count(c, "SELECT count(*) FROM outbox WHERE status = 'PENDING'");
        }
    }

    private static Connection connect(Database db) throws SQLException {
        Connection c = DriverManager.getConnection(db.url(), db.user(), db.password());
        c.setReadOnly(true);
        return c;
    }

    private static int count(Connection c, String sql) throws SQLException {
        return query(c, sql, rs -> rs.getInt(1)).get(0);
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private static <T> List<T> query(Connection c, String sql, RowMapper<T> mapper) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            List<T> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(mapper.map(rs));
            }
            return rows;
        }
    }

    private static UUID uuid(ResultSet rs, int column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
