package com.example.commitgap.core.scenario;

import java.time.Duration;

/**
 * How long the runner watches the system after the workload and the recovery.
 *
 * @param timeoutSeconds    hard deadline for the observation window
 * @param quietPeriodMillis how long nothing may change (no pending outbox rows, empty queue, unchanged
 *                          snapshot) before the runner considers the system settled
 */
public record Observation(int timeoutSeconds, int quietPeriodMillis) {

    public static final int DEFAULT_QUIET_PERIOD_MILLIS = 1500;

    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    public Duration quietPeriod() {
        return Duration.ofMillis(quietPeriodMillis);
    }
}
