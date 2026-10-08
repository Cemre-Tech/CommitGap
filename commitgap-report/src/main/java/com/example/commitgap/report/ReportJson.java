package com.example.commitgap.report;

import com.example.commitgap.core.CommitGapVersion;
import com.example.commitgap.core.invariant.InvariantResult;
import com.example.commitgap.core.model.Outcome;
import com.example.commitgap.core.result.EnvironmentInfo;
import com.example.commitgap.core.result.Expectation;
import com.example.commitgap.core.result.FaultExecution;
import com.example.commitgap.core.result.Measurements;
import com.example.commitgap.core.result.RunResult;
import com.example.commitgap.core.result.TimelineEvent;
import com.example.commitgap.core.scenario.Workload;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the versioned JSON report. The JSON layout is defined here field by field, not derived
 * from internal classes, so the schema ({@code report.schema.json}) changes only on purpose.
 *
 * <p>Version 1 layout: {@code reportSchemaVersion, kind, runId, generatedAt, cliVersion, summary,
 * matrix[], runs[]}. A single run is a report with one matrix cell and one run.
 */
public final class ReportJson {

    public static final int SCHEMA_VERSION = 1;

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(tools.jackson.databind.SerializationFeature.INDENT_OUTPUT)
            .build();

    private ReportJson() {
    }

    /**
     * @param kind "run", "compare" or "demo"
     */
    public static ObjectNode build(String kind, String runId, List<RunResult> runs, Instant generatedAt) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("reportSchemaVersion", SCHEMA_VERSION);
        root.put("kind", kind);
        root.put("runId", runId);
        root.put("generatedAt", generatedAt.toString());
        root.put("cliVersion", CommitGapVersion.version());
        root.set("summary", summary(runs));
        ArrayNode matrix = root.putArray("matrix");
        for (RunResult r : runs) {
            ObjectNode cell = matrix.addObject();
            cell.put("scenario", r.scenario().id());
            cell.put("strategy", r.strategy());
            cell.put("runId", r.runId());
            cell.put("outcome", r.outcome().name());
            cell.put("expected", r.expected() == null ? null : r.expected().name());
            cell.put("expectation", r.expectation().name());
        }
        ArrayNode list = root.putArray("runs");
        runs.forEach(r -> list.add(run(r)));
        return root;
    }

    private static ObjectNode summary(List<RunResult> runs) {
        ObjectNode s = MAPPER.createObjectNode();
        s.put("cells", runs.size());
        s.put("expectationMatched", runs.stream().filter(r -> r.expectation() == Expectation.MATCHED).count());
        s.put("expectationNotMatched", runs.stream().filter(r -> r.expectation() == Expectation.NOT_MATCHED).count());
        s.put("notVerified", runs.stream().filter(r -> r.expectation() == Expectation.NOT_VERIFIED).count());
        ObjectNode byOutcome = s.putObject("outcomes");
        for (Outcome o : Outcome.values()) {
            byOutcome.put(o.name(), runs.stream().filter(r -> r.outcome() == o).count());
        }
        return s;
    }

    static ObjectNode run(RunResult r) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("runId", r.runId());
        n.put("parentRunId", r.parentRunId());
        n.put("strategy", r.strategy());
        n.put("startedAt", str(r.startedAt()));
        n.put("finishedAt", str(r.finishedAt()));

        ObjectNode scenario = n.putObject("scenario");
        scenario.put("id", r.scenario().id());
        scenario.put("description", r.scenario().description());
        scenario.put("source", r.scenario().source());
        Workload w = r.scenario().workload();
        ObjectNode workload = scenario.putObject("workload");
        workload.put("seed", w.seed());
        workload.put("orders", w.orders());
        workload.put("quantityPerOrder", w.quantityPerOrder());
        workload.put("initialStock", w.initialStock());
        workload.put("rollbackEvery", w.rollbackEvery());
        workload.put("consumerWorkers", w.consumerWorkers());
        scenario.put("fault", r.scenario().fault());
        scenario.put("recovery", r.scenario().recovery());
        scenario.set("assertions", strings(r.scenario().assertions()));

        ObjectNode result = n.putObject("result");
        result.put("outcome", r.outcome().name());
        result.set("reasons", strings(r.outcomeReasons()));
        result.put("expected", r.expected() == null ? null : r.expected().name());
        result.put("expectation", r.expectation().name());
        result.set("obstacles", strings(r.obstacles()));

        ObjectNode observation = n.putObject("observation");
        observation.put("timeoutSeconds", r.observation().timeoutSeconds());
        observation.put("observedMillis", r.observation().observedMillis());
        observation.put("endReason", r.observation().endReason());

        n.set("fault", fault(r.fault()));
        ArrayNode invariants = n.putArray("invariants");
        r.invariants().forEach(i -> invariants.add(invariant(i)));
        n.set("measurements", measurements(r.measurements()));
        ArrayNode timeline = n.putArray("timeline");
        Instant origin = r.startedAt();
        r.timeline().forEach(e -> timeline.add(event(e, origin)));
        n.set("environment", environment(r.environment()));
        return n;
    }

    private static JsonNode fault(FaultExecution f) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("status", f.status().name());
        n.put("description", f.description());
        n.put("target", f.target());
        n.put("checkpoint", f.checkpoint());
        n.put("checkpointReachedAt", str(f.checkpointReachedAt()));
        n.set("checkpointContext", map(f.checkpointContext()));
        n.put("appliedAt", str(f.appliedAt()));
        n.put("verification", f.verification());
        n.put("recovery", f.recovery());
        n.put("recoveredAt", str(f.recoveredAt()));
        n.put("problem", f.problem());
        return n;
    }

    private static JsonNode invariant(InvariantResult i) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", i.id().id());
        n.put("description", i.id().description());
        n.put("status", i.status().name());
        n.put("expected", i.expected());
        n.put("actual", i.actual());
        n.put("explanation", i.explanation());
        ObjectNode evidence = n.putObject("evidence");
        i.evidence().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> evidence.set(e.getKey(), strings(e.getValue())));
        return n;
    }

    private static JsonNode measurements(Measurements m) {
        if (m == null) {
            return MAPPER.nullNode();
        }
        ObjectNode n = MAPPER.createObjectNode();
        n.put("plannedOrders", m.plannedOrders());
        n.put("committedOrders", m.committedOrders());
        n.put("uncommittedOrders", m.uncommittedOrders());
        n.put("publishAttempts", m.publishAttempts());
        n.put("confirmedPublishes", m.confirmedPublishes());
        n.put("brokerPublished", m.brokerPublished());
        n.put("brokerDeliveries", m.brokerDeliveries());
        n.put("brokerRedeliveries", m.brokerRedeliveries());
        n.put("consumerAttempts", m.consumerAttempts());
        n.put("consumerRedeliveries", m.consumerRedeliveries());
        n.put("businessEffects", m.businessEffects());
        n.put("eventsWithEffect", m.eventsWithEffect());
        n.put("duplicateEffects", m.duplicateEffects());
        n.put("committedOrdersWithoutEffect", m.committedOrdersWithoutEffect());
        n.put("outboxPending", m.outboxPending());
        n.put("outboxParked", m.outboxParked());
        n.put("queueReady", m.queueReady());
        n.put("queueUnacknowledged", m.queueUnacknowledged());
        n.put("deadLettered", m.deadLettered());
        n.put("initialStock", m.initialStock());
        n.put("expectedStock", m.expectedStock());
        n.put("finalStock", m.finalStock());
        return n;
    }

    private static JsonNode event(TimelineEvent e, Instant origin) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("at", str(e.at()));
        n.put("offsetMillis", origin == null || e.at() == null ? 0 : java.time.Duration.between(origin, e.at()).toMillis());
        n.put("source", e.source());
        n.put("kind", e.kind());
        n.put("message", e.message());
        n.set("attributes", map(e.attributes()));
        return n;
    }

    private static JsonNode environment(EnvironmentInfo env) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("cliVersion", env.cliVersion());
        n.put("reportSchemaVersion", SCHEMA_VERSION);
        n.put("javaVersion", env.javaVersion());
        n.put("os", env.os());
        n.put("dockerServerVersion", env.dockerServerVersion());
        n.put("resourceNamespace", env.resourceNamespace());
        n.set("images", map(env.images()));
        n.set("imageIds", map(env.imageIds()));
        n.put("demoArtifactSha256", env.demoArtifactSha256());
        n.set("components", map(env.components()));
        n.set("resources", strings(env.resources()));
        n.put("credentials", "generated per run; never recorded");
        return n;
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode a = MAPPER.createArrayNode();
        values.forEach(a::add);
        return a;
    }

    private static ObjectNode map(Map<String, String> values) {
        ObjectNode o = MAPPER.createObjectNode();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> o.put(e.getKey(), e.getValue()));
        return o;
    }

    private static String str(Instant t) {
        return t == null ? null : t.toString();
    }
}
