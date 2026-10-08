package com.example.commitgap.demo.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.example.commitgap.demo.events.OrderCreatedEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the consumer's business transaction against a real PostgreSQL, including two workers
 * processing the same event at the same moment.
 */
@Testcontainers
class StockLedgerIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    private static final String SKU = "SKU-001";

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(ds).locations("classpath:db/consumer").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        pool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void concurrentDuplicateWaitsOnTheUniqueKeyAndSkips() throws Exception {
        StockLedger ledger = ledger(true);
        OrderCreatedEvent event = event();
        CountDownLatch holdFirst = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        ledger.afterDedupInsert(() -> {
            if (first.getAndSet(false)) {
                block(holdFirst);
            }
        });

        Future<StockLedger.Outcome> a = pool.submit(() -> ledger.apply(event));
        awaitDedupInsertHeld();
        Future<StockLedger.Outcome> b = pool.submit(() -> ledger.apply(event));
        // The second transaction is now blocked on the processed_message primary key, not polling a flag.
        await().atMost(Duration.ofSeconds(10)).until(this::sessionsWaitingOnLocks, n -> n >= 1);
        holdFirst.countDown();

        assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(StockLedger.Outcome.APPLIED, StockLedger.Outcome.DUPLICATE_SKIPPED);
        assertSingleEffect(event);
    }

    @Test
    void whenTheFirstTransactionRollsBackTheWaitingDuplicateAppliesTheEvent() throws Exception {
        StockLedger ledger = ledger(true);
        OrderCreatedEvent event = event();
        CountDownLatch failFirst = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        ledger.afterDedupInsert(() -> {
            if (first.getAndSet(false)) {
                block(failFirst);
                throw new IllegalStateException("simulated failure after the dedup insert");
            }
        });

        Future<StockLedger.Outcome> a = pool.submit(() -> ledger.apply(event));
        awaitDedupInsertHeld();
        Future<StockLedger.Outcome> b = pool.submit(() -> ledger.apply(event));
        await().atMost(Duration.ofSeconds(10)).until(this::sessionsWaitingOnLocks, n -> n >= 1);
        failFirst.countDown();

        assertThatThrownBy(() -> a.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(StockLedger.Outcome.APPLIED);
        assertSingleEffect(event);
    }

    @Test
    void manySimultaneousDuplicatesLeaveOneEffect() throws Exception {
        StockLedger ledger = ledger(true);
        OrderCreatedEvent event = event();
        int workers = 8;
        CyclicBarrier start = new CyclicBarrier(workers);
        List<Future<StockLedger.Outcome>> results = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            results.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return ledger.apply(event);
            }));
        }

        List<StockLedger.Outcome> outcomes = new ArrayList<>();
        for (Future<StockLedger.Outcome> f : results) {
            outcomes.add(f.get(20, TimeUnit.SECONDS));
        }

        assertThat(outcomes).filteredOn(o -> o == StockLedger.Outcome.APPLIED).hasSize(1);
        assertSingleEffect(event);
    }

    @Test
    void nonIdempotentConsumerAppliesEveryDelivery() {
        StockLedger ledger = ledger(false);
        OrderCreatedEvent event = event();

        ledger.apply(event);
        ledger.apply(event);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE event_id = ?", Integer.class,
                event.eventId())).isEqualTo(2);
        assertThat(stock()).isEqualTo(98);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_message", Integer.class)).isZero();
    }

    @Test
    void failedBusinessTransactionIsNotCountedAsProcessed() {
        StockLedger ledger = ledger(true);
        OrderCreatedEvent unknownSku = new OrderCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), "SKU-404", 1);

        assertThatThrownBy(() -> ledger.apply(unknownSku)).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_message", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement", Integer.class)).isZero();
    }

    private StockLedger ledger(boolean idempotent) {
        StockLedger ledger = new StockLedger(jdbc, tx, idempotent, "stock-consumer");
        ledger.seedStock(SKU, 100);
        return ledger;
    }

    private static OrderCreatedEvent event() {
        return new OrderCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), SKU, 1);
    }

    private void awaitDedupInsertHeld() {
        // The first transaction holds its uncommitted dedup row: it is "idle in transaction".
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE state = 'idle in transaction' AND datname = current_database()",
                Integer.class), n -> n >= 1);
    }

    private int sessionsWaitingOnLocks() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                + "AND datname = current_database()", Integer.class);
    }

    private void assertSingleEffect(OrderCreatedEvent event) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE event_id = ?", Integer.class,
                event.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_message WHERE event_id = ?", Integer.class,
                event.eventId())).isEqualTo(1);
        assertThat(stock()).isEqualTo(99);
    }

    private int stock() {
        return jdbc.queryForObject("SELECT quantity FROM stock WHERE sku = ?", Integer.class, SKU);
    }

    private static void block(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
