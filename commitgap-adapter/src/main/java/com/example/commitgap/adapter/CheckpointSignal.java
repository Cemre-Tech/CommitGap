package com.example.commitgap.adapter;

import com.example.commitgap.core.model.Checkpoint;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/** Signal only after the named stage has truly completed. Gate waits must have a bounded duration. */
@FunctionalInterface
public interface CheckpointSignal {
    void hit(Checkpoint checkpoint, UUID eventId) throws InterruptedException, TimeoutException;

    static CheckpointSignal disabled() {
        return (checkpoint, eventId) -> { };
    }
}
