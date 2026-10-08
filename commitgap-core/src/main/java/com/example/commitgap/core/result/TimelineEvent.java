package com.example.commitgap.core.result;

import java.time.Instant;
import java.util.Map;

/**
 * One entry of the run timeline. Timeline entries explain what happened; they are never used as
 * evidence of a business effect, which only comes from database snapshots.
 *
 * @param source "runner" for actions the runner took or observed, or a process role for rows that process logged
 * @param kind   a short machine-readable category such as checkpoint-reached, fault-applied, publish, delivery
 */
public record TimelineEvent(Instant at, String source, String kind, String message, Map<String, String> attributes) {

    public TimelineEvent {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static TimelineEvent runner(String kind, String message) {
        return new TimelineEvent(Instant.now(), "runner", kind, message, Map.of());
    }

    public static TimelineEvent runner(String kind, String message, Map<String, String> attributes) {
        return new TimelineEvent(Instant.now(), "runner", kind, message, attributes);
    }
}
