package com.example.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.commitgap.adapter.CheckpointSignal;
import com.example.commitgap.adapter.ExternalApplicationVerifier;
import com.example.commitgap.core.invariant.InvariantId;
import com.example.commitgap.core.invariant.InvariantStatus;
import com.example.commitgap.core.model.Outcome;
import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.scenario.Workload;
import com.example.commitgap.core.workload.WorkloadPlan;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ExternalSpringExampleTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;

    @BeforeEach
    void freshApplicationOwnedDatabase() {
        var datasource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(datasource);
        transactions = new DataSourceTransactionManager(datasource);
    }

    @Test
    void committedProducerCrashExposesActualLostEvent() throws Exception {
        var execution = ExampleCases.execute(jdbc, transactions, "loss");
        assertThat(execution.verification().classification().outcome()).isEqualTo(Outcome.VIOLATION_OBSERVED);
        assertThat(execution.verification().measurements().committedOrders()).isEqualTo(1);
        assertThat(execution.verification().measurements().businessEffects()).isZero();
        var missing = execution.verification().invariants().stream()
                .filter(i -> i.id() == InvariantId.COMMITTED_ORDER_HAS_BUSINESS_EFFECT).findFirst().orElseThrow();
        assertThat(missing.evidence().get("missingEffectEventIds")).containsExactly(execution.eventId().toString());
    }

    @Test
    void consumerPostCommitFaultProducesTwoActualLedgerRowsWithoutDeduplication() throws Exception {
        var execution = ExampleCases.execute(jdbc, transactions, "duplicate");
        assertThat(execution.verification().classification().outcome()).isEqualTo(Outcome.VIOLATION_OBSERVED);
        assertThat(execution.verification().measurements().businessEffects()).isEqualTo(2);
        assertThat(execution.verification().measurements().duplicateEffects()).isEqualTo(1);
        assertThat(execution.verification().measurements().finalStock()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_effect WHERE event_id=?", Integer.class,
                execution.eventId())).isEqualTo(2);
    }

    @Test
    void samePostCommitFaultKeepsOneActualEffectWithAtomicDeduplication() throws Exception {
        var execution = ExampleCases.execute(jdbc, transactions, "safe");
        assertThat(execution.verification().classification().outcome()).isEqualTo(Outcome.CONSISTENT);
        assertThat(execution.verification().measurements().businessEffects()).isEqualTo(1);
        assertThat(execution.verification().measurements().brokerDeliveries()).isEqualTo(2);
        assertThat(execution.verification().measurements().finalStock()).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id=?", Integer.class,
                execution.eventId())).isEqualTo(1);
    }

    @Test
    void snapshotsRemainScopedWhenRunsHaveIdenticalDeterministicEventIds() throws Exception {
        ExampleCases.execute(jdbc, transactions, "duplicate");
        var safe = ExampleCases.execute(jdbc, transactions, "safe");
        assertThat(safe.verification().classification().outcome()).isEqualTo(Outcome.CONSISTENT);
        assertThat(safe.verification().measurements().committedOrders()).isEqualTo(1);
        assertThat(safe.verification().measurements().businessEffects()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_effect", Integer.class)).isEqualTo(3);
    }

    @Test
    void actualRollbackCreatesNeitherCommittedOrderNorBusinessEffect() throws Exception {
        var plan = WorkloadPlan.from(new Workload(42, 2, 2, 10, 2, 1));
        var app = new InventoryService(jdbc, transactions, Strategy.OUTBOX_IDEMPOTENT, plan);
        for (var order : plan.orders()) {
            app.produce(order, CheckpointSignal.disabled());
        }
        app.relay(plan);
        app.consume(CheckpointSignal.disabled(), false);
        app.workloadCompleted();
        var result = ExternalApplicationVerifier.verify(plan, Strategy.OUTBOX_IDEMPOTENT, app);
        assertThat(result.classification().outcome()).isEqualTo(Outcome.CONSISTENT);
        assertThat(result.measurements().committedOrders()).isEqualTo(1);
        assertThat(result.measurements().businessEffects()).isEqualTo(1);
        assertThat(result.invariants()).filteredOn(i -> i.id() == InvariantId.ROLLED_BACK_ORDER_HAS_NO_EFFECT)
                .allSatisfy(i -> assertThat(i.status()).isEqualTo(InvariantStatus.PASS));
    }

    @Test
    void writerStartingAndFinishingBetweenSnapshotReadsCannotBeReportedQuiescent() throws Exception {
        var firstReadFinished = new CountDownLatch(1);
        var continueCapture = new CountDownLatch(1);
        // The first real SELECT establishes the repeatable-read snapshot before the writer starts.
        var blockedJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public <T> java.util.List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                var rows = super.query(sql, mapper, args);
                if (sql.startsWith("SELECT * FROM purchase_order")) {
                    firstReadFinished.countDown();
                    try {
                        if (!continueCapture.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Snapshot test barrier timed out");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Snapshot test barrier interrupted", e);
                    }
                }
                return rows;
            }
        };
        var plan = WorkloadPlan.from(new Workload(42, 1, 2, 10, 0, 1));
        var app = new InventoryService(blockedJdbc, transactions, Strategy.NAIVE_DUAL_WRITE, plan);
        app.workloadCompleted();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var capture = executor.submit(() -> app.capture(plan));
            try {
                assertThat(firstReadFinished.await(5, TimeUnit.SECONDS)).isTrue();
                app.produce(plan.orders().getFirst(), CheckpointSignal.disabled());
            } finally {
                continueCapture.countDown();
            }
            var observation = capture.get(5, TimeUnit.SECONDS);
            assertThat(observation.snapshot().producer().orders()).isEmpty();
            assertThat(observation.snapshot().broker().published()).isEqualTo(1);
            assertThat(observation.quiescent()).isFalse();
            var result = ExternalApplicationVerifier.verify(plan, Strategy.NAIVE_DUAL_WRITE, ignored -> observation);
            assertThat(result.classification().outcome()).isEqualTo(Outcome.INCONCLUSIVE);
            assertThat(result.classification().reasons()).anyMatch(r -> r.contains("not quiescent"));
        }
    }
}
