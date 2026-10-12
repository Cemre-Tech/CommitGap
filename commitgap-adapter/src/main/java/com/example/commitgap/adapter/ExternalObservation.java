package com.example.commitgap.adapter;

import com.example.commitgap.core.snapshot.LabSnapshot;
import java.util.List;
import java.util.Objects;

/**
 * @param workloadCompleted all planned requests, including intentional rollbacks, were exercised
 * @param quiescent no producer, relay, or consumer can change this run's data during capture;
 *                  pending durable work may still exist and is represented in the snapshot
 * @param obstacles safe, credential-free descriptions of measurement limitations
 */
public record ExternalObservation(LabSnapshot snapshot, boolean workloadCompleted, boolean quiescent,
                                  List<String> obstacles) {
    public ExternalObservation {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(snapshot.producer(), "producer snapshot");
        Objects.requireNonNull(snapshot.consumer(), "consumer snapshot");
        Objects.requireNonNull(snapshot.broker(), "broker snapshot");
        Objects.requireNonNull(snapshot.capturedAt(), "capture time");
        obstacles = List.copyOf(obstacles);
    }
}
