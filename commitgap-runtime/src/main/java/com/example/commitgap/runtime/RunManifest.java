package com.example.commitgap.runtime;

import java.time.Instant;
import java.util.List;

/**
 * Bookkeeping for one run directory. Lists the Docker resources the run created so that kept
 * resources are visible and {@code commitgap cleanup} can report what it removed.
 *
 * @param kind     "run" for one scenario/strategy cell, "compare" or "demo" for a parent of several cells
 * @param status   running, completed, error, not-applicable or cleaned
 * @param children run ids of the cells of a compare/demo run
 */
public record RunManifest(
        int manifestVersion,
        String runId,
        String parentRunId,
        String kind,
        List<String> scenarios,
        List<String> strategies,
        Instant startedAt,
        Instant finishedAt,
        String status,
        boolean resourcesKept,
        List<String> resources,
        List<String> children,
        String cliVersion) {

    public static final int VERSION = 1;

    public RunManifest {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        strategies = strategies == null ? List.of() : List.copyOf(strategies);
        resources = resources == null ? List.of() : List.copyOf(resources);
        children = children == null ? List.of() : List.copyOf(children);
    }

    public RunManifest withStatus(String newStatus, Instant finished, boolean kept, List<String> currentResources) {
        return new RunManifest(manifestVersion, runId, parentRunId, kind, scenarios, strategies, startedAt, finished,
                newStatus, kept, currentResources, children, cliVersion);
    }

    public RunManifest withChildren(List<String> newChildren) {
        return new RunManifest(manifestVersion, runId, parentRunId, kind, scenarios, strategies, startedAt, finishedAt,
                status, resourcesKept, resources, newChildren, cliVersion);
    }
}
