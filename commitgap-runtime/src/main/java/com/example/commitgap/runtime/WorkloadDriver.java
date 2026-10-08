package com.example.commitgap.runtime;

import com.example.commitgap.core.model.FaultAction;
import com.example.commitgap.core.model.ProcessRole;
import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.result.FaultExecution;
import com.example.commitgap.core.scenario.Fault;
import com.example.commitgap.core.workload.PlannedOrder;
import com.example.commitgap.core.workload.WorkloadPlan;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Sends the planned orders to the producer one at a time. Applies network faults at their order
 * index, and waits for the fault supervisor when a producer request was cut off by a kill. The
 * outcome of an interrupted request is never guessed: it is read from the database later.
 */
final class WorkloadDriver {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration IDLE_DEADLINE = Duration.ofSeconds(30);

    private final Lab lab;
    private final WorkloadPlan plan;
    private final Strategy strategy;
    private final Fault networkFault;
    private final FaultSupervisor supervisor;
    private final Timeline timeline;
    private final List<String> obstacles;
    private final Duration startupTimeout;

    private FaultExecution networkExecution;
    private int failedAttemptsBeforeCut;

    WorkloadDriver(Lab lab, WorkloadPlan plan, Strategy strategy, Fault fault, FaultSupervisor supervisor,
                   Timeline timeline, List<String> obstacles, Duration startupTimeout) {
        this.lab = lab;
        this.plan = plan;
        this.strategy = strategy;
        this.networkFault = fault != null && fault.action() == FaultAction.NETWORK_CUT ? fault : null;
        this.supervisor = supervisor;
        this.timeline = timeline;
        this.obstacles = obstacles;
        this.startupTimeout = startupTimeout;
    }

    FaultExecution networkExecution() {
        return networkExecution;
    }

    void run() {
        ControlClient producer = lab.require(ProcessRole.PRODUCER).control();
        timeline.runner("workload-started", plan.orders().size() + " orders, seed " + plan.workload().seed());
        int restoreAfter = networkFault == null ? -1 : networkFault.atOrder() + networkFault.ordersDuringFault() - 1;
        for (PlannedOrder order : plan.orders()) {
            if (networkFault != null && order.number() == networkFault.atOrder() && !cutLink()) {
                return;
            }
            if (!send(producer, order)) {
                return;
            }
            if (order.number() == restoreAfter) {
                restoreLink();
            }
        }
        timeline.runner("workload-finished", "all orders sent");
    }

    /** Returns false when the workload cannot continue. */
    private boolean send(ControlClient producer, PlannedOrder order) {
        Map<String, String> attrs = Map.of("order", String.valueOf(order.number()), "eventId", order.eventId().toString());
        try {
            ControlClient.Response response = producer.createOrder(order, REQUEST_TIMEOUT);
            int status = response.status();
            if (status == 201) {
                timeline.runner("order", "order " + order.number() + " committed", attrs);
            } else if (status == 202) {
                timeline.runner("order", "order " + order.number() + " committed but its event was not published: "
                        + text(response, "publishResult") + " " + text(response, "detail"), attrs);
            } else if (status == 409 && order.rollback()) {
                timeline.runner("order", "order " + order.number() + " rolled back (planned)", attrs);
            } else {
                obstacles.add("order " + order.number() + ": unexpected HTTP " + status + " from the producer");
                timeline.runner("order-error", "order " + order.number() + " returned HTTP " + status, attrs);
            }
            return true;
        } catch (IOException e) {
            if (supervisor != null && supervisor.targetRole() == ProcessRole.PRODUCER) {
                timeline.runner("order-interrupted", "order " + order.number()
                        + " request ended without a response (" + e.getClass().getSimpleName()
                        + "); whether it committed is read from the database", attrs);
                if (!supervisor.awaitDone(startupTimeout.plusSeconds(30))) {
                    obstacles.add("producer did not come back after the fault");
                    return false;
                }
                return lab.require(ProcessRole.PRODUCER).isRunning();
            }
            obstacles.add("order " + order.number() + ": producer unreachable: " + e.getMessage());
            timeline.runner("order-error", "order " + order.number() + " failed: " + e.getMessage(), attrs);
            return false;
        }
    }

    private boolean cutLink() {
        try {
            awaitPublisherIdle();
            failedAttemptsBeforeCut = lab.snapshotReader().failedPublishAttempts();
            lab.cutBrokerLink();
            Instant applied = Instant.now();
            boolean enabled = lab.brokerLinkEnabled();
            String verification = enabled ? "Toxiproxy still reports the link enabled" : "Toxiproxy reports the link disabled";
            timeline.runner("fault-applied", "cut publisher-to-broker link before order " + networkFault.atOrder()
                    + " (" + verification + ")");
            networkExecution = new FaultExecution(enabled ? FaultExecution.Status.NOT_VERIFIED : FaultExecution.Status.APPLIED,
                    networkFault.describe(), networkFault.target().id(), null, null, Map.of(), applied, verification,
                    null, null, enabled ? "link still enabled after cut" : null);
            return true;
        } catch (IOException | SQLException e) {
            obstacles.add("could not cut the broker link: " + e.getMessage());
            networkExecution = new FaultExecution(FaultExecution.Status.NOT_VERIFIED, networkFault.describe(),
                    networkFault.target().id(), null, null, Map.of(), null, null, null, null, e.getMessage());
            return false;
        }
    }

    private void restoreLink() {
        try {
            observeOutageEffect();
            lab.restoreBrokerLink();
            boolean enabled = lab.brokerLinkEnabled();
            timeline.runner("recovered", "restored publisher-to-broker link after order " + (networkFault.atOrder()
                    + networkFault.ordersDuringFault() - 1) + (enabled ? "" : " (Toxiproxy still reports it disabled)"));
            FaultExecution e = networkExecution;
            networkExecution = new FaultExecution(enabled ? e.status() : FaultExecution.Status.RECOVERY_FAILED,
                    e.description(), e.target(), null, null, Map.of(), e.appliedAt(), e.verification(),
                    "restore-network", Instant.now(), enabled ? e.problem() : "link not restored");
        } catch (IOException e) {
            obstacles.add("could not restore the broker link: " + e.getMessage());
        }
    }

    /**
     * Keeps the link cut until the outage has demonstrably reached the publisher: the publish log shows a
     * failed attempt made after the cut. Without this, a fast workload could restore the link before the
     * relay ever tried to publish, and the outage would not have been tested at all.
     */
    private void observeOutageEffect() {
        SnapshotReader reader = lab.snapshotReader();
        Instant until = Instant.now().plus(IDLE_DEADLINE);
        try {
            while (reader.failedPublishAttempts() <= failedAttemptsBeforeCut) {
                if (Instant.now().isAfter(until)) {
                    obstacles.add("no publish attempt failed during the broker outage within "
                            + IDLE_DEADLINE.toSeconds() + "s; the outage did not reach the publisher");
                    return;
                }
                DemoProcess.sleep(50);
            }
            int failed = reader.failedPublishAttempts() - failedAttemptsBeforeCut;
            String backlog = strategy.usesOutboxRelay() ? reader.pendingOutbox() + " outbox row(s) pending" : "no outbox";
            timeline.runner("outage-observed", failed + " publish attempt(s) failed during the outage; " + backlog,
                    Map.of("failedAttempts", String.valueOf(failed)));
        } catch (SQLException e) {
            obstacles.add("could not observe the outage: " + e.getMessage());
        }
    }

    /**
     * The cut is applied while nothing is being published, so the outage affects only orders accepted
     * during it. (A cut in the middle of a confirm round-trip is a different, ambiguous case.)
     */
    private void awaitPublisherIdle() throws SQLException {
        if (!strategy.usesOutboxRelay()) {
            return; // the naive producer publishes synchronously inside the previous request
        }
        SnapshotReader reader = lab.snapshotReader();
        Instant until = Instant.now().plus(IDLE_DEADLINE);
        while (reader.pendingOutbox() > 0) {
            if (Instant.now().isAfter(until)) {
                throw new SQLException("outbox did not drain within " + IDLE_DEADLINE.toSeconds() + "s before the cut");
            }
            DemoProcess.sleep(50);
        }
    }

    private static String text(ControlClient.Response response, String field) {
        return response.body() == null ? "" : response.body().path(field).asString("");
    }
}
