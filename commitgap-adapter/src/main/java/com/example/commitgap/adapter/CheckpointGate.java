package com.example.commitgap.adapter;

import com.example.commitgap.core.model.Checkpoint;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A one-shot in-process checkpoint gate. Nonmatching events continue immediately. A matching worker
 * pauses until released, cooperatively aborted, interrupted, or timed out. This does not kill an OS
 * process and cannot stand in for the Docker runner's SIGKILL experiments.
 */
public final class CheckpointGate implements CheckpointSignal, AutoCloseable {
    public record Reached(Checkpoint checkpoint, UUID eventId) {
    }

    /** Unchecked so it also aborts a transactional callback; placement determines what has committed. */
    public static final class FaultInjectedException extends RuntimeException {
        private FaultInjectedException(Reached reached) {
            super("Cooperative fault at " + reached.checkpoint().id() + " for event " + reached.eventId());
        }
    }

    private final Reached target;
    private final long maxPauseNanos;
    private final AtomicBoolean used = new AtomicBoolean();
    private final CompletableFuture<Reached> reached = new CompletableFuture<>();
    private final CompletableFuture<Boolean> decision = new CompletableFuture<>();

    public CheckpointGate(Checkpoint checkpoint, UUID eventId, Duration maxPause) {
        target = new Reached(Objects.requireNonNull(checkpoint, "checkpoint"), Objects.requireNonNull(eventId, "eventId"));
        maxPauseNanos = positiveNanos(maxPause);
    }

    @Override
    public void hit(Checkpoint checkpoint, UUID eventId) throws InterruptedException, TimeoutException {
        if (!target.checkpoint().equals(checkpoint) || !target.eventId().equals(eventId)
                || !used.compareAndSet(false, true)) {
            return;
        }
        reached.complete(target);
        try {
            if (decision.get(maxPauseNanos, TimeUnit.NANOSECONDS)) {
                throw new FaultInjectedException(target);
            }
        } catch (ExecutionException e) {
            throw new IllegalStateException("Checkpoint decision failed", e);
        }
    }

    public Reached awaitReached(Duration timeout) throws InterruptedException, TimeoutException {
        try {
            return reached.get(positiveNanos(timeout), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Checkpoint signal failed", e);
        }
    }

    /** First decision wins. Only call after awaitReached returns successfully. */
    public void release() {
        decide(false);
    }

    /** Stops the matching worker cooperatively at the signaled boundary; recovery is caller-owned. */
    public void abort() {
        decide(true);
    }

    private void decide(boolean abort) {
        if (!reached.isDone()) {
            throw new IllegalStateException("The checkpoint has not been reached");
        }
        decision.complete(abort);
    }

    /** Closing safely releases any worker and disarms a gate that was never reached. */
    @Override
    public void close() {
        used.set(true);
        decision.complete(false);
    }

    private static long positiveNanos(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Checkpoint duration must be positive");
        }
        return duration.toNanos();
    }
}
