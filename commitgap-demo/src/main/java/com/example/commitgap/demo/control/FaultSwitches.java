package com.example.commitgap.demo.control;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Faults that change what a process does rather than stopping it. Currently only duplicate
 * publishing: the n-th distinct event this process publishes is sent {@code copies} times with the
 * same event id. Counting distinct events keeps retries of one event from shifting the count.
 */
@Component
public class FaultSwitches {

    private final Set<UUID> seenEvents = ConcurrentHashMap.newKeySet();
    private volatile int duplicateOccurrence;
    private volatile int duplicateCopies = 1;

    public synchronized void armDuplicatePublish(int occurrence, int copies) {
        this.duplicateOccurrence = occurrence;
        this.duplicateCopies = copies;
    }

    /** How many times to publish this event. Called once per publish of an event, including retries. */
    public synchronized int copiesFor(UUID eventId) {
        boolean first = seenEvents.add(eventId);
        if (first && duplicateOccurrence > 0 && seenEvents.size() == duplicateOccurrence) {
            return duplicateCopies;
        }
        return 1;
    }

    public int duplicateOccurrence() {
        return duplicateOccurrence;
    }

    public int duplicateCopies() {
        return duplicateCopies;
    }
}
