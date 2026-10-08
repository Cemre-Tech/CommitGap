package com.example.commitgap.demo.control;

import com.example.commitgap.core.model.Checkpoint;
import com.example.commitgap.demo.DemoProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deterministic fault points. Code calls {@link #reach} only after the stage named by the checkpoint
 * has really completed. When the runner armed that checkpoint for this occurrence, the calling thread
 * records its arrival in memory and blocks until the runner releases it or (typically) kills the
 * process. Arrivals are kept in memory, never in the business database, so recording them cannot be
 * rolled back with or influence a business transaction; the runner copies them as soon as it sees them.
 */
@Component
public class CheckpointGate {

    private static final Logger log = LoggerFactory.getLogger(CheckpointGate.class);

    public record Arming(Checkpoint checkpoint, int occurrence) {
    }

    public record Arrival(Checkpoint checkpoint, int occurrence, Instant at, Map<String, String> context,
                          String state) {
    }

    private final long barrierTimeoutSeconds;
    private final Map<Checkpoint, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final List<Arrival> arrivals = new CopyOnWriteArrayList<>();
    private volatile Arming armed;
    private volatile CountDownLatch release = new CountDownLatch(1);

    public CheckpointGate(DemoProperties properties) {
        this.barrierTimeoutSeconds = properties.barrierTimeoutSeconds();
    }

    public synchronized void arm(Checkpoint checkpoint, int occurrence) {
        armed = new Arming(checkpoint, occurrence);
        release = new CountDownLatch(1);
        log.info("checkpoint armed: {} occurrence {}", checkpoint.id(), occurrence);
    }

    public void release(Checkpoint checkpoint) {
        Arming a = armed;
        if (a != null && a.checkpoint() == checkpoint) {
            release.countDown();
        }
    }

    /**
     * Called after the stage has completed. Returns immediately unless this exact occurrence is armed.
     */
    public void reach(Checkpoint checkpoint, Map<String, String> context) {
        int occurrence = counts.computeIfAbsent(checkpoint, c -> new AtomicInteger()).incrementAndGet();
        Arming a = armed;
        if (a == null || a.checkpoint() != checkpoint || a.occurrence() != occurrence) {
            return;
        }
        CountDownLatch latch = release;
        arrivals.add(new Arrival(checkpoint, occurrence, Instant.now(), Map.copyOf(context), "waiting"));
        log.info("checkpoint reached: {} occurrence {} {} - waiting for the runner", checkpoint.id(), occurrence, context);
        boolean released;
        try {
            released = latch.await(barrierTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            released = false;
        }
        arrivals.add(new Arrival(checkpoint, occurrence, Instant.now(), Map.copyOf(context),
                released ? "released" : "barrier-timeout"));
        log.info("checkpoint {} continuing ({})", checkpoint.id(), released ? "released" : "barrier timeout");
    }

    public Arming armed() {
        return armed;
    }

    public Map<String, Integer> counts() {
        Map<String, Integer> result = new java.util.TreeMap<>();
        counts.forEach((k, v) -> result.put(k.id(), v.get()));
        return result;
    }

    public List<Arrival> arrivals() {
        return List.copyOf(arrivals);
    }
}
