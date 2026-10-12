package com.example.inventory;

import com.example.commitgap.adapter.CheckpointGate;
import com.example.commitgap.adapter.CheckpointSignal;
import com.example.commitgap.adapter.ExternalApplicationVerifier;
import com.example.commitgap.core.model.Checkpoint;
import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.scenario.Workload;
import com.example.commitgap.core.workload.WorkloadPlan;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Caller-owned workload and recovery; the adapter only coordinates checkpoints and reads evidence. */
public final class ExampleCases {
    private ExampleCases() {
    }

    public record Execution(UUID eventId, ExternalApplicationVerifier.Verification verification) {
    }

    public static Execution execute(JdbcTemplate jdbc, PlatformTransactionManager transactions, String name) throws Exception {
        Strategy strategy = switch (name) {
            case "loss" -> Strategy.NAIVE_DUAL_WRITE;
            case "duplicate" -> Strategy.TRANSACTIONAL_OUTBOX;
            case "safe" -> Strategy.OUTBOX_IDEMPOTENT;
            default -> throw new IllegalArgumentException("Unknown example case. Use loss, duplicate, safe, or all.");
        };
        WorkloadPlan plan = WorkloadPlan.from(new Workload(42, 1, 2, 10, 0, 1));
        var order = plan.orders().getFirst();
        var app = new InventoryService(jdbc, transactions, strategy, plan);
        Checkpoint stage = "loss".equals(name) ? Checkpoint.PRODUCER_AFTER_DB_COMMIT_BEFORE_PUBLISH
                : Checkpoint.CONSUMER_AFTER_BUSINESS_COMMIT_BEFORE_ACK;
        if (strategy.usesOutboxRelay()) {
            app.produce(order, CheckpointSignal.disabled());
            app.relay(plan);
        }
        try (var gate = new CheckpointGate(stage, order.eventId(), Duration.ofSeconds(5));
             var executor = Executors.newSingleThreadExecutor()) {
            var worker = executor.submit(() -> {
                if ("loss".equals(name)) {
                    app.produce(order, gate);
                } else {
                    app.consume(gate, false);
                }
                return null;
            });
            gate.awaitReached(Duration.ofSeconds(5));
            gate.abort();
            try {
                worker.get(5, TimeUnit.SECONDS);
                throw new IllegalStateException("The requested cooperative fault was not applied");
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof CheckpointGate.FaultInjectedException)) {
                    throw e;
                }
            }
        }
        if (!"loss".equals(name)) {
            app.consume(CheckpointSignal.disabled(), true);
        }
        app.workloadCompleted();
        return new Execution(order.eventId(), ExternalApplicationVerifier.verify(plan, strategy, app));
    }
}
