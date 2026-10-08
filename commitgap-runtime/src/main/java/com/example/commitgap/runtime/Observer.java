package com.example.commitgap.runtime;

import com.example.commitgap.core.scenario.Observation;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Watches the system after the workload until it has settled or the observation deadline passes.
 * Settled means: any pending fault has been handled, no outbox row is pending, the broker holds no
 * ready or unacknowledged message, and none of these counters changed for the quiet period. The
 * observer only decides when to take the snapshot; it does not judge the result.
 */
final class Observer {

    record Result(long observedMillis, String endReason, String lastProblem) {
    }

    private final SnapshotReader reader;
    private final BrokerProbe broker;
    private final boolean usesOutbox;
    private final FaultSupervisor supervisor;

    Observer(SnapshotReader reader, BrokerProbe broker, boolean usesOutbox, FaultSupervisor supervisor) {
        this.reader = reader;
        this.broker = broker;
        this.usesOutbox = usesOutbox;
        this.supervisor = supervisor;
    }

    Result observe(Observation observation) {
        Instant start = Instant.now();
        Instant deadline = start.plus(observation.timeout());
        Duration quiet = observation.quietPeriod();
        Object lastFingerprint = null;
        Instant stableSince = start;
        String problem = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                SnapshotReader.QuickState db = reader.quickState();
                BrokerProbe.Depth b = broker.depth();
                Object fingerprint = java.util.List.of(db, b);
                if (!Objects.equals(fingerprint, lastFingerprint)) {
                    lastFingerprint = fingerprint;
                    stableSince = Instant.now();
                }
                boolean idle = (supervisor == null || supervisor.isDone())
                        && (!usesOutbox || db.pendingOutbox() == 0)
                        && b.backlog() == 0;
                problem = null;
                if (idle && Duration.between(stableSince, Instant.now()).compareTo(quiet) >= 0) {
                    return new Result(Duration.between(start, Instant.now()).toMillis(), "settled", null);
                }
            } catch (Exception e) {
                problem = e.getMessage();
            }
            DemoProcess.sleep(200);
        }
        return new Result(Duration.between(start, Instant.now()).toMillis(), "timeout", problem);
    }
}
