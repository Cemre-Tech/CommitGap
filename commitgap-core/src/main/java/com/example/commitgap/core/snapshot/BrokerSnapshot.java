package com.example.commitgap.core.snapshot;

/**
 * Queue state reported by the broker's management API.
 *
 * @param measured     false when the broker could not be queried; the counters are then meaningless
 * @param deliveries   deliveries to consumers reported by the broker, including redeliveries
 */
public record BrokerSnapshot(boolean measured, long ready, long unacknowledged, long deadLettered, long published,
                             long deliveries, long redeliveries, String error) {

    public static BrokerSnapshot unavailable(String error) {
        return new BrokerSnapshot(false, 0, 0, 0, 0, 0, 0, error);
    }

    public long backlog() {
        return ready + unacknowledged;
    }
}
