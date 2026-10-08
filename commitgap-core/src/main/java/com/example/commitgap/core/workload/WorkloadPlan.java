package com.example.commitgap.core.workload;

import com.example.commitgap.core.scenario.Workload;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * The deterministic order list derived from a workload. {@link java.util.Random} is used because its
 * algorithm is fixed by the Java specification, so the same seed yields the same order and event ids
 * on every JVM. Event ids are generated once here and never regenerated on retries.
 */
public final class WorkloadPlan {

    public static final String SKU = "SKU-001";

    private final Workload workload;
    private final List<PlannedOrder> orders;
    private final Map<UUID, PlannedOrder> byOrderId;
    private final Map<UUID, PlannedOrder> byEventId;

    private WorkloadPlan(Workload workload, List<PlannedOrder> orders) {
        this.workload = workload;
        this.orders = Collections.unmodifiableList(orders);
        this.byOrderId = new LinkedHashMap<>();
        this.byEventId = new LinkedHashMap<>();
        for (PlannedOrder o : orders) {
            byOrderId.put(o.orderId(), o);
            byEventId.put(o.eventId(), o);
        }
    }

    public static WorkloadPlan from(Workload workload) {
        Random random = new Random(workload.seed());
        List<PlannedOrder> orders = new ArrayList<>(workload.orders());
        for (int n = 1; n <= workload.orders(); n++) {
            UUID orderId = randomUuid(random);
            UUID eventId = randomUuid(random);
            orders.add(new PlannedOrder(n, orderId, eventId, SKU, workload.quantityPerOrder(), workload.isRolledBack(n)));
        }
        return new WorkloadPlan(workload, orders);
    }

    /** A version-4 style UUID drawn from the seeded generator. */
    static UUID randomUuid(Random random) {
        long msb = random.nextLong();
        long lsb = random.nextLong();
        msb = (msb & ~0x000000000000F000L) | 0x0000000000004000L;
        lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    public Workload workload() {
        return workload;
    }

    public List<PlannedOrder> orders() {
        return orders;
    }

    public Optional<PlannedOrder> byOrderId(UUID orderId) {
        return Optional.ofNullable(byOrderId.get(orderId));
    }

    public Optional<PlannedOrder> byEventId(UUID eventId) {
        return Optional.ofNullable(byEventId.get(eventId));
    }
}
