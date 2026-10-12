package com.example.commitgap.runtime;

import com.example.commitgap.core.model.Checkpoint;
import com.example.commitgap.core.model.ProcessRole;
import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.result.FaultExecution;
import com.example.commitgap.core.scenario.Fault;
import com.example.commitgap.core.snapshot.DeliveryRecord;
import com.example.commitgap.core.snapshot.PublishAttempt;
import com.example.commitgap.core.workload.PlannedOrder;
import com.example.commitgap.core.workload.WorkloadPlan;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;

/**
 * Drops real broker-to-publisher bytes on an already established AMQP connection. Acceptance is
 * proven by the directly connected consumer's delivery record, and the missing confirm by a real
 * TIMEOUT in the publisher's log. Nothing fabricates a publisher result or retries in the runner.
 */
final class ConfirmLossFault {

    private static final Duration DEADLINE = Duration.ofSeconds(30);
    private static final Checkpoint RETRY_BARRIER = Checkpoint.RELAY_AFTER_PUBLISHER_TIMEOUT_BEFORE_RETRY;

    private final Lab lab;
    private final Strategy strategy;
    private final Fault fault;
    private final Timeline timeline;
    private final List<String> obstacles;

    private boolean toxicInstalled;
    private boolean retryBarrierArmed;
    private int timeoutOccurrence;
    private boolean verified;
    private boolean recovered;
    private Instant appliedAt;
    private Instant recoveredAt;
    private String problem;
    private String verification;
    private Map<String, String> context = Map.of();

    ConfirmLossFault(Lab lab, Strategy strategy, Fault fault, Timeline timeline, List<String> obstacles) {
        this.lab = lab;
        this.strategy = strategy;
        this.fault = fault;
        this.timeline = timeline;
        this.obstacles = obstacles;
    }

    boolean execute(WorkloadPlan plan, PlannedOrder order, BooleanSupplier sendOrder) {
        try {
            awaitWarmPublisher(plan, order);
            if (strategy.usesOutboxRelay()) {
                ControlClient relay = lab.require(ProcessRole.RELAY).control();
                timeoutOccurrence = relay.checkpoints().path("counts").path(RETRY_BARRIER.id()).asInt(0) + 1;
                retryBarrierArmed = true;
                relay.armCheckpoint(RETRY_BARRIER, timeoutOccurrence);
            }
            toxicInstalled = true;
            lab.dropPublisherResponses();
            appliedAt = Instant.now();
            if (!lab.publisherResponsesDropped()) {
                throw new IOException("daemon did not report the downstream timeout toxic on the enabled proxy");
            }
            timeline.runner("downstream-toxic-installed", "dropping broker-to-publisher bytes for order " + order.number()
                    + "; upstream remains enabled", Map.of("eventId", order.eventId().toString(), "stream", "downstream"));
            if (!sendOrder.getAsBoolean()) {
                throw new IOException("producer request failed while downstream responses were dropped");
            }
            verifyAcceptanceAndTimeout(order);
        } catch (IOException | SQLException | RuntimeException e) {
            fail("confirm-loss was not verified: " + e.getMessage());
        } finally {
            recover();
        }
        return verified && recovered && problem == null;
    }

    /** A successful earlier publish warms the channel; an empty outbox prevents unrelated attempts. */
    private void awaitWarmPublisher(WorkloadPlan plan, PlannedOrder order) throws SQLException, IOException {
        PlannedOrder previous = plan.orders().stream().filter(o -> o.number() < order.number() && !o.rollback())
                .reduce((a, b) -> b).orElseThrow(() -> new IOException("no earlier committed order could warm the publisher"));
        Instant until = Instant.now().plus(DEADLINE);
        SnapshotReader reader = lab.snapshotReader();
        while (Instant.now().isBefore(until)) {
            boolean confirmed = reader.producer().publishAttempts().stream().anyMatch(a ->
                    previous.eventId().equals(a.eventId()) && "CONFIRMED".equals(a.result()));
            boolean delivered = reader.consumer().deliveries().stream().anyMatch(d -> previous.eventId().equals(d.eventId()));
            boolean idle = !strategy.usesOutboxRelay() || reader.pendingOutbox() == 0;
            if (confirmed && delivered && idle) {
                timeline.runner("publisher-warmed", "previous event was confirmed and delivered; publisher is idle",
                        Map.of("eventId", previous.eventId().toString()));
                return;
            }
            DemoProcess.sleep(50);
        }
        throw new IOException("publisher did not become warm and idle within " + DEADLINE.toSeconds() + "s");
    }

    private void verifyAcceptanceAndTimeout(PlannedOrder order) throws SQLException, IOException {
        Instant until = Instant.now().plus(DEADLINE);
        SnapshotReader reader = lab.snapshotReader();
        while (Instant.now().isBefore(until)) {
            PublishAttempt timeout = reader.producer().publishAttempts().stream().filter(a ->
                    order.eventId().equals(a.eventId()) && "TIMEOUT".equals(a.result()))
                    .findFirst().orElse(null);
            DeliveryRecord delivery = reader.consumer().deliveries().stream().filter(d ->
                    order.eventId().equals(d.eventId())).findFirst().orElse(null);
            if (timeout != null && delivery != null && retryIsHeld()) {
                if (!lab.publisherResponsesDropped()) {
                    throw new IOException("downstream toxic disappeared before acceptance and timeout could be verified");
                }
                context = Map.of("eventId", order.eventId().toString(), "order", String.valueOf(order.number()),
                        "stream", "downstream", "publishResult", "TIMEOUT",
                        "consumerReceivedAt", delivery.receivedAt().toString(), "publisherTimedOutAt", timeout.at().toString());
                verification = "consumer received event " + order.eventId()
                        + " while broker-to-publisher bytes were dropped; publisher recorded a real TIMEOUT";
                verified = true;
                timeline.runner("confirm-loss-observed", verification, context);
                timeline.runner("fault-applied", "downstream confirm loss verified for order " + order.number(), context);
                return;
            }
            DemoProcess.sleep(50);
        }
        throw new IOException("no target-event delivery plus real publisher TIMEOUT within " + DEADLINE.toSeconds() + "s");
    }

    private boolean retryIsHeld() throws IOException {
        if (!strategy.usesOutboxRelay()) {
            return true; // The naive producer returned its synchronous result and has no retry mechanism.
        }
        JsonNode state = lab.require(ProcessRole.RELAY).control().checkpoints();
        for (JsonNode arrival : state.path("arrivals")) {
            if (RETRY_BARRIER.id().equals(arrival.path("checkpoint").asString())
                    && arrival.path("occurrence").asInt() == timeoutOccurrence
                    && "waiting".equals(arrival.path("state").asString())) {
                return true;
            }
        }
        return false;
    }

    private void recover() {
        boolean restored = false;
        try {
            if (toxicInstalled) {
                lab.restorePublisherResponses();
                restored = !lab.publisherResponsesDropped() && lab.brokerLinkEnabled();
                if (!restored) {
                    throw new IOException("downstream toxic was not removed from the enabled proxy");
                }
            }
        } catch (IOException | RuntimeException e) {
            fail("could not restore publisher responses: " + e.getMessage());
        } finally {
            if (retryBarrierArmed) {
                try {
                    lab.require(ProcessRole.RELAY).control().releaseCheckpoint(RETRY_BARRIER);
                } catch (RuntimeException e) {
                    restored = false;
                    fail("could not release the relay retry barrier: " + e.getMessage());
                }
            }
        }
        recovered = restored;
        if (restored) {
            recoveredAt = Instant.now();
            timeline.runner("recovered", "removed downstream toxic; the publisher can reconnect and the relay may retry", context);
        }
    }

    private void fail(String message) {
        problem = problem == null ? message : problem + "; " + message;
        obstacles.add(message);
        timeline.runner("confirm-loss-error", message);
    }

    FaultExecution result() {
        FaultExecution.Status status = verified
                ? recovered ? FaultExecution.Status.APPLIED : FaultExecution.Status.RECOVERY_FAILED
                : FaultExecution.Status.NOT_VERIFIED;
        return new FaultExecution(status, fault.describe(), fault.target().id(), null, null, context, appliedAt,
                verification, "restore-network", recoveredAt, problem);
    }
}
