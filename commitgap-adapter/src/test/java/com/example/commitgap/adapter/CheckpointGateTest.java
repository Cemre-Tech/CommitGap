package com.example.commitgap.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.commitgap.core.model.Checkpoint;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class CheckpointGateTest {
    private static final Checkpoint STAGE = Checkpoint.PRODUCER_AFTER_DB_COMMIT_BEFORE_PUBLISH;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void onlyMatchingEventBlocksAndReleaseContinuesWorker() throws Exception {
        UUID event = UUID.randomUUID();
        try (var gate = new CheckpointGate(STAGE, event, TIMEOUT);
             var executor = Executors.newSingleThreadExecutor()) {
            gate.hit(STAGE, UUID.randomUUID());
            gate.hit(Checkpoint.CONSUMER_AFTER_BUSINESS_COMMIT_BEFORE_ACK, event);
            var worker = executor.submit(() -> {
                gate.hit(STAGE, event);
                return "continued";
            });
            assertThat(gate.awaitReached(TIMEOUT).eventId()).isEqualTo(event);
            assertThat(worker.isDone()).isFalse();
            gate.release();
            assertThat(worker.get(5, TimeUnit.SECONDS)).isEqualTo("continued");
            // Recovery can hit the same boundary without another pause.
            gate.hit(STAGE, event);
        }
    }

    @Test
    void abortStopsMatchingWorkerAtBoundary() throws Exception {
        UUID event = UUID.randomUUID();
        try (var gate = new CheckpointGate(STAGE, event, TIMEOUT);
             var executor = Executors.newSingleThreadExecutor()) {
            var worker = executor.submit(() -> {
                gate.hit(STAGE, event);
                return "should not continue";
            });
            gate.awaitReached(TIMEOUT);
            gate.abort();
            assertThatThrownBy(() -> worker.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(CheckpointGate.FaultInjectedException.class);
        }
    }

    @Test
    void workerAndControllerWaitsAreBounded() throws Exception {
        UUID event = UUID.randomUUID();
        try (var gate = new CheckpointGate(STAGE, event, Duration.ofMillis(10))) {
            assertThatThrownBy(() -> gate.awaitReached(Duration.ofMillis(10)))
                    .isInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> gate.hit(STAGE, event)).isInstanceOf(TimeoutException.class);
        }
    }

    @Test
    void closeDisarmsAnUnreachedGateAndDecisionsRequireAReachedWorker() throws Exception {
        UUID event = UUID.randomUUID();
        var gate = new CheckpointGate(STAGE, event, TIMEOUT);
        assertThatThrownBy(gate::abort).isInstanceOf(IllegalStateException.class);
        gate.close();
        gate.hit(STAGE, event);
        assertThatThrownBy(() -> new CheckpointGate(STAGE, event, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closeReleasesAnAlreadyPausedWorker() throws Exception {
        UUID event = UUID.randomUUID();
        var gate = new CheckpointGate(STAGE, event, TIMEOUT);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var worker = executor.submit(() -> {
                gate.hit(STAGE, event);
                return true;
            });
            gate.awaitReached(TIMEOUT);
            gate.close();
            assertThat(worker.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            gate.close();
        }
    }
}
