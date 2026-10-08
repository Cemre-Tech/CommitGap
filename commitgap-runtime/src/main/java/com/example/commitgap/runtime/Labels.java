package com.example.commitgap.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Docker labels put on every resource the lab creates. Cleanup selects resources only by these
 * labels, so it can never touch containers, networks or databases that CommitGap did not create.
 */
public final class Labels {

    public static final String MANAGED = "commitgap.managed";
    public static final String RUN = "commitgap.run";
    public static final String PARENT_RUN = "commitgap.parent-run";
    public static final String COMPONENT = "commitgap.component";

    private Labels() {
    }

    public static Map<String, String> forRun(String runId, String parentRunId, String component) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(MANAGED, "true");
        labels.put(RUN, runId);
        if (parentRunId != null) {
            labels.put(PARENT_RUN, parentRunId);
        }
        labels.put(COMPONENT, component);
        return labels;
    }

    /** Name prefix for every Docker resource of a run. */
    public static String namespace(String runId) {
        return "commitgap-" + runId;
    }
}
