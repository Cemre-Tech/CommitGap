package com.example.commitgap.runtime;

import com.example.commitgap.core.result.TimelineEvent;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The runner's own record of what it did and saw. Each event is appended to {@code timeline.jsonl}
 * and flushed immediately, so the checkpoint that was reached and the fault that was applied are
 * preserved even if a target process (or the runner) dies right afterwards.
 */
public final class Timeline {

    private final List<TimelineEvent> events = new ArrayList<>();
    private final BufferedWriter writer;

    public Timeline(Path file) {
        try {
            Files.createDirectories(file.getParent());
            this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new LabException("cannot write timeline " + file + ": " + e.getMessage(), e);
        }
    }

    public synchronized TimelineEvent record(TimelineEvent event) {
        events.add(event);
        try {
            writer.write(Json.COMPACT.writeValueAsString(event));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            // The in-memory record is still kept; the report will contain it.
        }
        return event;
    }

    public TimelineEvent runner(String kind, String message) {
        return record(TimelineEvent.runner(kind, message));
    }

    public TimelineEvent runner(String kind, String message, Map<String, String> attributes) {
        return record(TimelineEvent.runner(kind, message, attributes));
    }

    /** Runner events plus events derived from process logs, sorted by time. */
    public synchronized List<TimelineEvent> merged(List<TimelineEvent> processEvents) {
        List<TimelineEvent> all = new ArrayList<>(events);
        all.addAll(processEvents);
        all.sort(Comparator.comparing(TimelineEvent::at));
        return all;
    }

    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
