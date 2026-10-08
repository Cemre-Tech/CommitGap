package com.example.commitgap.report;

import com.example.commitgap.core.invariant.InvariantEvaluator;
import com.example.commitgap.core.invariant.InvariantId;
import com.example.commitgap.core.invariant.InvariantResult;
import com.example.commitgap.core.model.Outcome;
import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.result.EnvironmentInfo;
import com.example.commitgap.core.result.Expectation;
import com.example.commitgap.core.result.FaultExecution;
import com.example.commitgap.core.result.Measurements;
import com.example.commitgap.core.result.OutcomeClassifier;
import com.example.commitgap.core.result.RunResult;
import com.example.commitgap.core.result.TimelineEvent;
import com.example.commitgap.core.scenario.Workload;
import com.example.commitgap.core.snapshot.BrokerSnapshot;
import com.example.commitgap.core.snapshot.ConsumerSnapshot;
import com.example.commitgap.core.snapshot.LabSnapshot;
import com.example.commitgap.core.snapshot.OrderRow;
import com.example.commitgap.core.snapshot.ProducerSnapshot;
import com.example.commitgap.core.snapshot.StockMovement;
import com.example.commitgap.core.workload.PlannedOrder;
import com.example.commitgap.core.workload.WorkloadPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds run results the way the runtime does: from a workload plan and database rows, through the
 * real evaluator and classifier. Only the rows are hand-made.
 */
final class Fixtures {

    static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private Fixtures() {
    }

    /** A naive run in which the producer was killed after committing order 3; its event never had an effect. */
    static RunResult naiveCrash(String description) {
        Workload workload = new Workload(42, 6, 1, 100, 0, 1);
        WorkloadPlan plan = WorkloadPlan.from(workload);
        List<OrderRow> orders = new ArrayList<>();
        List<StockMovement> movements = new ArrayList<>();
        int stock = 100;
        PlannedOrder lost = plan.orders().get(2);
        long id = 1;
        for (PlannedOrder o : plan.orders()) {
            orders.add(new OrderRow(o.orderId(), o.eventId(), o.sku(), o.quantity(), T0));
            if (o != lost) {
                movements.add(new StockMovement(id++, o.eventId(), o.orderId(), o.sku(), -1, T0.plusSeconds(1)));
                stock--;
            }
        }
        LabSnapshot snapshot = new LabSnapshot(new ProducerSnapshot(orders, List.of(), List.of()),
                new ConsumerSnapshot(stock, movements, List.of(), List.of()),
                new BrokerSnapshot(true, 0, 0, 0, -1, -1, -1, null), T0.plusSeconds(5));
        List<InvariantResult> invariants = new InvariantEvaluator(plan, Strategy.NAIVE_DUAL_WRITE, snapshot)
                .evaluate(List.of(InvariantId.values()));
        FaultExecution fault = new FaultExecution(FaultExecution.Status.APPLIED, "SIGKILL producer", "producer",
                "producer.after-db-commit-before-publish", T0.plusMillis(300),
                Map.of("eventId", lost.eventId().toString()), T0.plusMillis(320),
                "container stopped, exit code 137", "restart", T0.plusSeconds(3), null);
        var c = OutcomeClassifier.classify(true, List.of(), fault, invariants);
        List<TimelineEvent> timeline = List.of(
                new TimelineEvent(T0.plusMillis(300), "runner", "checkpoint-reached", "producer reached checkpoint",
                        Map.of("eventId", lost.eventId().toString())),
                new TimelineEvent(T0.plusMillis(320), "runner", "fault-applied", "SIGKILL; exit code 137", Map.of()));
        return new RunResult("20260101-100000-abc123", null,
                new RunResult.ScenarioSummary("crash-after-commit", description, "crash-after-commit.yaml", workload,
                        "SIGKILL producer at producer.after-db-commit-before-publish (occurrence 3)", "restart",
                        List.of("committed-order-has-business-effect")),
                Strategy.NAIVE_DUAL_WRITE.id(), T0, T0.plusSeconds(6),
                new RunResult.ObservationSummary(30, 2100, "settled"),
                c.outcome(), c.reasons(), Outcome.VIOLATION_OBSERVED,
                Expectation.compare(c.outcome(), Outcome.VIOLATION_OBSERVED), invariants,
                Measurements.of(plan, Strategy.NAIVE_DUAL_WRITE, snapshot), fault, timeline, environment(), List.of());
    }

    /** A cell whose lab never started: no snapshot, an obstacle, and therefore INCONCLUSIVE. */
    static RunResult environmentFailure() {
        Workload workload = new Workload(42, 6, 1, 100, 0, 1);
        FaultExecution fault = new FaultExecution(FaultExecution.Status.NOT_VERIFIED, "SIGKILL producer", "producer",
                null, null, Map.of(), null, null, null, null, "run ended before the fault was verified");
        List<String> obstacles = List.of("environment could not be prepared: Docker is not available");
        var c = OutcomeClassifier.classify(true, obstacles, fault, List.of());
        return new RunResult("20260101-100000-abc123-outbox-idempotent", "20260101-100000-abc123",
                new RunResult.ScenarioSummary("crash-after-commit", "Kill the producer.", "crash-after-commit.yaml",
                        workload, "SIGKILL producer", "restart", List.of("committed-order-has-business-effect")),
                Strategy.OUTBOX_IDEMPOTENT.id(), T0, T0.plusSeconds(1),
                new RunResult.ObservationSummary(30, 0, "skipped"), c.outcome(), c.reasons(), Outcome.CONSISTENT,
                Expectation.compare(c.outcome(), Outcome.CONSISTENT), List.of(), null, fault, List.of(),
                environment(), obstacles);
    }

    static EnvironmentInfo environment() {
        return new EnvironmentInfo("0.1.0-SNAPSHOT", "Eclipse Adoptium 21.0.12", "Linux 6.8 (amd64)", "29.6.1",
                "commitgap-20260101-100000-abc123", Map.of("postgres", "postgres:18.6-alpine"),
                Map.of("postgres", "sha256:0123456789abcdef0123"), "ab12", Map.of("spring-boot", "4.1.1"),
                List.of("container commitgap-20260101-100000-abc123-producer"));
    }
}
