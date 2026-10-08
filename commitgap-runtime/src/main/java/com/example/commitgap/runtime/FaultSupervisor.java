package com.example.commitgap.runtime;

import com.example.commitgap.core.model.Checkpoint;
import com.example.commitgap.core.model.ProcessRole;
import com.example.commitgap.core.model.RecoveryAction;
import com.example.commitgap.core.result.FaultExecution;
import com.example.commitgap.core.scenario.Fault;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;

/**
 * Applies a checkpoint-bound kill. The target process announces that it reached the armed
 * checkpoint and blocks; the supervisor sees the arrival, records it in the runner's timeline, sends
 * SIGKILL, verifies that Docker reports the container stopped with exit code 137, and performs the
 * recovery. Nothing is timed: the kill happens because the checkpoint was observed, never after a sleep.
 */
final class FaultSupervisor {

    private static final long POLL_MILLIS = 50;

    private final Fault fault;
    private final Checkpoint checkpoint;
    private final DemoProcess target;
    private final RecoveryAction recovery;
    private final DemoProcess relayToOpen;
    private final Timeline timeline;
    private final Duration startupTimeout;
    private final CountDownLatch done = new CountDownLatch(1);
    private final java.util.concurrent.atomic.AtomicBoolean relayOpened = new java.util.concurrent.atomic.AtomicBoolean();

    private volatile boolean stopRequested;
    private volatile FaultExecution.Status status;
    private volatile Instant reachedAt;
    private volatile Map<String, String> context = Map.of();
    private volatile Instant appliedAt;
    private volatile String verification;
    private volatile Instant recoveredAt;
    private volatile String problem;
    private Thread thread;

    /**
     * @param relayToOpen relay whose gate is closed until the producer checkpoint has been handled, or null
     */
    FaultSupervisor(Fault fault, DemoProcess target, RecoveryAction recovery, DemoProcess relayToOpen,
                    Timeline timeline, Duration startupTimeout) {
        this.fault = fault;
        this.checkpoint = fault.checkpoint().orElseThrow();
        this.target = target;
        this.recovery = recovery;
        this.relayToOpen = relayToOpen;
        this.timeline = timeline;
        this.startupTimeout = startupTimeout;
    }

    ProcessRole targetRole() {
        return target.role();
    }

    void armAndStart() {
        target.control().armCheckpoint(checkpoint, fault.occurrence());
        timeline.runner("checkpoint-armed", "armed " + checkpoint.id() + " (occurrence " + fault.occurrence()
                + ") on " + target.containerName(), Map.of("checkpoint", checkpoint.id()));
        thread = Thread.ofPlatform().name("fault-supervisor").daemon(true).start(this::watch);
    }

    private void watch() {
        try {
            while (!stopRequested) {
                JsonNode arrival = waitingArrival();
                if (arrival != null) {
                    handle(arrival);
                    return;
                }
                DemoProcess.sleep(POLL_MILLIS);
            }
            status = FaultExecution.Status.CHECKPOINT_NOT_REACHED;
            problem = "checkpoint " + checkpoint.id() + " occurrence " + fault.occurrence()
                    + " was not reached before the observation window closed";
            timeline.runner("checkpoint-not-reached", problem);
        } catch (RuntimeException e) {
            status = FaultExecution.Status.NOT_VERIFIED;
            problem = "fault supervisor failed: " + e.getMessage();
            timeline.runner("fault-error", problem);
        } finally {
            openRelayGate(status == FaultExecution.Status.APPLIED ? "producer checkpoint handled" : "fault finished");
            done.countDown();
        }
    }

    private JsonNode waitingArrival() {
        JsonNode state;
        try {
            state = target.control().checkpoints();
        } catch (IOException e) {
            return null; // not answering right now; keep watching until stopped
        }
        if (state == null) {
            return null;
        }
        for (JsonNode a : state.path("arrivals")) {
            if (checkpoint.id().equals(a.path("checkpoint").asString())
                    && a.path("occurrence").asInt() == fault.occurrence()
                    && "waiting".equals(a.path("state").asString())) {
                return a;
            }
        }
        return null;
    }

    private void handle(JsonNode arrival) {
        reachedAt = Instant.now();
        Map<String, String> ctx = new LinkedHashMap<>();
        arrival.path("context").properties().forEach(e -> ctx.put(e.getKey(), e.getValue().asString()));
        ctx.put("processReportedAt", arrival.path("at").asString());
        context = Map.copyOf(ctx);
        timeline.runner("checkpoint-reached", target.role().id() + " reached " + checkpoint.id() + " and is waiting",
                context);

        timeline.runner("kill-sent", "sending SIGKILL to " + target.containerName());
        DemoProcess.KillResult kill = target.kill(Duration.ofSeconds(15));
        appliedAt = Instant.now();
        verification = kill.detail();
        timeline.runner("fault-applied", "SIGKILL " + target.containerName() + ": " + kill.detail(),
                Map.of("signal", "SIGKILL", "exitCode", String.valueOf(kill.exitCode())));
        if (!kill.verified()) {
            status = FaultExecution.Status.NOT_VERIFIED;
            problem = "kill could not be verified: " + kill.detail();
            return;
        }

        if (recovery == RecoveryAction.RESTART) {
            try {
                target.restart(startupTimeout);
                recoveredAt = Instant.now();
                timeline.runner("recovered", "restarted " + target.containerName() + "; control endpoint healthy");
            } catch (RuntimeException e) {
                status = FaultExecution.Status.RECOVERY_FAILED;
                problem = "restart failed: " + e.getMessage();
                timeline.runner("recovery-failed", problem);
                return;
            }
        }
        status = FaultExecution.Status.APPLIED;
    }

    /** Opens the relay barrier once. Also called by the runner when the workload ended without the checkpoint. */
    void openRelayGate(String reason) {
        if (relayToOpen == null || !relayOpened.compareAndSet(false, true)) {
            return;
        }
        try {
            relayToOpen.control().setRelayGate(true);
            timeline.runner("relay-barrier-opened", "relay may publish now (" + reason + ")");
        } catch (RuntimeException e) {
            timeline.runner("relay-barrier-error", "could not open relay gate: " + e.getMessage());
        }
    }

    boolean isDone() {
        return done.getCount() == 0;
    }

    boolean awaitDone(Duration timeout) {
        try {
            return done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Stops waiting for the checkpoint. If it was not reached by now, the fault counts as not applied. */
    void stop() {
        stopRequested = true;
        awaitDone(startupTimeout.plusSeconds(30));
    }

    FaultExecution result() {
        return new FaultExecution(status == null ? FaultExecution.Status.CHECKPOINT_NOT_REACHED : status,
                fault.describe(), target.role().id(), checkpoint.id(), reachedAt, context, appliedAt, verification,
                recovery.id(), recoveredAt, problem);
    }
}
