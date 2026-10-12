package com.example.commitgap.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.commitgap.core.snapshot.ConsumerSnapshot;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** A writer commits between the reader's stock and ledger queries, without timing-based sleeps. */
class SnapshotReaderIT {

    @Test
    void consumerSnapshotDoesNotMixStockBeforeACommitWithLedgerAfterIt() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(LabImages.POSTGRES)) {
            postgres.start();
            SnapshotReader.Database database = new SnapshotReader.Database(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
            try (Connection writer = DriverManager.getConnection(database.url(), database.user(), database.password());
                 Statement sql = writer.createStatement();
                 ExecutorService pool = Executors.newSingleThreadExecutor()) {
                sql.execute("""
                        CREATE TABLE stock_base (sku text PRIMARY KEY, quantity integer NOT NULL);
                        INSERT INTO stock_base VALUES ('SKU-001', 100);
                        CREATE FUNCTION snapshot_barrier() RETURNS boolean LANGUAGE plpgsql VOLATILE AS $$
                        BEGIN PERFORM pg_advisory_xact_lock(741029); RETURN true; END $$;
                        CREATE VIEW stock AS SELECT sku, quantity FROM stock_base WHERE snapshot_barrier();
                        CREATE TABLE stock_movement (id bigserial, event_id uuid, order_id uuid, sku text,
                            delta integer, applied_at timestamptz DEFAULT clock_timestamp());
                        CREATE TABLE processed_message (consumer_name text, event_id uuid,
                            processed_at timestamptz DEFAULT clock_timestamp());
                        CREATE TABLE delivery_log (id bigserial, event_id uuid, redelivered boolean,
                            worker text, outcome text, received_at timestamptz DEFAULT clock_timestamp());
                        """);
                sql.execute("SELECT pg_advisory_lock(741029)");
                SnapshotReader reader = new SnapshotReader(database, database);
                Future<ConsumerSnapshot> reading = pool.submit(reader::consumer);
                try {
                    // The first SELECT has established its MVCC snapshot and waits at the test barrier.
                    await().atMost(Duration.ofSeconds(15)).until(() -> {
                        try (ResultSet locks = sql.executeQuery(
                                "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")) {
                            locks.next();
                            return locks.getInt(1) == 1;
                        }
                    });
                    UUID event = UUID.randomUUID();
                    UUID order = UUID.randomUUID();
                    writer.setAutoCommit(false);
                    sql.executeUpdate("UPDATE stock_base SET quantity = 99");
                    sql.executeUpdate("INSERT INTO stock_movement (event_id, order_id, sku, delta) VALUES ('"
                            + event + "', '" + order + "', 'SKU-001', -1)");
                    sql.executeUpdate("INSERT INTO processed_message (consumer_name, event_id) VALUES ('stock-consumer', '"
                            + event + "')");
                    writer.commit();
                    writer.setAutoCommit(true);
                    sql.execute("SELECT pg_advisory_unlock(741029)");

                    ConsumerSnapshot before = reading.get(15, TimeUnit.SECONDS);
                    assertThat(before.stockQuantity()).isEqualTo(100);
                    assertThat(before.movements()).as("ledger must share the stock query's snapshot").isEmpty();
                    assertThat(before.processed()).isEmpty();

                    ConsumerSnapshot after = reader.consumer();
                    assertThat(after.stockQuantity()).isEqualTo(99);
                    assertThat(after.movements()).singleElement().satisfies(m -> assertThat(m.eventId()).isEqualTo(event));
                    assertThat(after.processed()).singleElement().satisfies(p -> assertThat(p.eventId()).isEqualTo(event));
                } finally {
                    if (!writer.getAutoCommit()) {
                        writer.rollback();
                        writer.setAutoCommit(true);
                    }
                    sql.execute("SELECT pg_advisory_unlock(741029)");
                    reading.cancel(true);
                    pool.shutdownNow();
                }
            }
        }
    }
}
