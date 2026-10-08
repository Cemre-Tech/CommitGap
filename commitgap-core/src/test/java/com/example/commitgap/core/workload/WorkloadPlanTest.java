package com.example.commitgap.core.workload;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.commitgap.core.scenario.Workload;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkloadPlanTest {

    @Test
    void sameSeedGivesSameOrderAndEventIds() {
        Workload w = new Workload(42, 20, 1, 100, 7, 1);

        assertThat(WorkloadPlan.from(w).orders()).isEqualTo(WorkloadPlan.from(w).orders());
    }

    @Test
    void seedFortyTwoIsStableAcrossJvms() {
        // Regression pin: java.util.Random's algorithm is part of the Java specification, so this value
        // must not change between JVMs or CommitGap versions without a schema change.
        PlannedOrder first = WorkloadPlan.from(new Workload(42, 1, 1, 100, 0, 1)).orders().get(0);

        assertThat(first.orderId()).isEqualTo(UUID.fromString("ba419d35-0dfe-4af7-aee7-bbe10c45c028"));
    }

    @Test
    void differentSeedsGiveDifferentIds() {
        var a = WorkloadPlan.from(new Workload(1, 5, 1, 100, 0, 1)).orders().get(0);
        var b = WorkloadPlan.from(new Workload(2, 5, 1, 100, 0, 1)).orders().get(0);

        assertThat(a.eventId()).isNotEqualTo(b.eventId());
    }

    @Test
    void idsAreUniqueVersion4Uuids() {
        WorkloadPlan plan = WorkloadPlan.from(new Workload(99, 500, 1, 1000, 0, 1));
        Set<UUID> seen = new HashSet<>();

        for (PlannedOrder o : plan.orders()) {
            assertThat(seen.add(o.orderId())).isTrue();
            assertThat(seen.add(o.eventId())).isTrue();
            assertThat(o.eventId().version()).isEqualTo(4);
            assertThat(o.eventId().variant()).isEqualTo(2);
        }
    }

    @Test
    void rollbackPatternFollowsRollbackEvery() {
        WorkloadPlan plan = WorkloadPlan.from(new Workload(42, 20, 1, 100, 7, 1));

        assertThat(plan.orders().stream().filter(PlannedOrder::rollback).map(PlannedOrder::number))
                .containsExactly(7, 14);
        assertThat(plan.workload().committedOrderCount()).isEqualTo(18);
    }
}
