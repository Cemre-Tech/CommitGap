package com.example.commitgap.runtime;

import java.nio.file.Path;
import java.time.Duration;

/**
 * @param demoJar          the runnable demo jar copied into the producer, relay and consumer containers
 * @param runsDirectory    where run directories (manifest, timeline, logs, reports) are written
 * @param startupTimeout   deadline for each container to become healthy
 * @param keep             when to keep Docker resources after a run instead of removing them
 */
public record RuntimeSettings(
        Path demoJar,
        Path runsDirectory,
        Duration startupTimeout,
        KeepPolicy keep,
        ProgressListener progress) {

    public enum KeepPolicy {
        /** Always remove the run's resources. */
        NEVER,
        /** Keep them when the outcome is inconclusive, the expectation did not match, or the run failed. */
        ON_FAILURE,
        /** Always keep them (cleanup later with {@code commitgap cleanup --run}). */
        ALWAYS
    }

    /** Receives short human-readable progress messages. */
    @FunctionalInterface
    public interface ProgressListener {
        void step(String runId, String message);

        static ProgressListener silent() {
            return (runId, message) -> { };
        }
    }
}
