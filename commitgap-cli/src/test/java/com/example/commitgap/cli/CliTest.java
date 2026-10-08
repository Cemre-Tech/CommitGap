package com.example.commitgap.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.commitgap.core.invariant.InvariantEvaluator;
import com.example.commitgap.core.invariant.InvariantId;
import com.example.commitgap.core.model.Outcome;
import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.result.EnvironmentInfo;
import com.example.commitgap.core.result.Expectation;
import com.example.commitgap.core.result.FaultExecution;
import com.example.commitgap.core.result.Measurements;
import com.example.commitgap.core.result.RunResult;
import com.example.commitgap.core.scenario.Workload;
import com.example.commitgap.core.snapshot.BrokerSnapshot;
import com.example.commitgap.core.snapshot.ConsumerSnapshot;
import com.example.commitgap.core.snapshot.LabSnapshot;
import com.example.commitgap.core.snapshot.ProducerSnapshot;
import com.example.commitgap.core.workload.WorkloadPlan;
import com.example.commitgap.report.ReportFiles;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Command-line behaviour that does not need Docker: parsing, validation, exit codes, saved reports. */
class CliTest {

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int run(String... args) {
        CommandLine cmd = CommitGapCli.newCommandLine();
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        return cmd.execute(args);
    }

    @Test
    void versionAndHelp() {
        assertThat(run("--version")).isZero();
        assertThat(out.toString()).contains("commitgap ", "report schema 1");
        assertThat(run("--help")).isZero();
        assertThat(out.toString()).contains("doctor", "demo", "scenarios", "run", "compare", "report", "cleanup");
    }

    @Test
    void missingOptionsAreUsageErrors() {
        assertThat(run("run")).isEqualTo(2);
        assertThat(err.toString()).contains("--scenario", "--strategy");
    }

    @Test
    void unknownStrategyIsAUsageError() {
        assertThat(run("run", "--scenario", "happy-path", "--strategy", "two-phase-commit")).isEqualTo(2);
        assertThat(err.toString()).contains("unknown strategy 'two-phase-commit'");
    }

    @Test
    void inapplicableScenarioIsRefusedBeforeStartingAnything() {
        assertThat(run("run", "--scenario", "relay-confirm-gap", "--strategy", "naive-dual-write")).isEqualTo(2);
        assertThat(err.toString()).contains("does not apply to naive-dual-write", "NOT_APPLICABLE");
    }

    @Test
    void invalidScenarioFileIsAUsageError(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.yaml");
        Files.writeString(file, "schemaVersion: 1\nid: broken\nid: again\n");

        assertThat(run("run", "--scenario", file.toString(), "--strategy", "outbox-idempotent")).isEqualTo(2);
        assertThat(err.toString()).contains("duplicate key id");
    }

    @Test
    void listsBundledScenarios() {
        assertThat(run("scenarios", "list")).isZero();
        assertThat(out.toString()).contains("happy-path", "crash-after-commit", "relay-confirm-gap",
                "duplicate-delivery", "consumer-ack-gap", "broker-outage", "naive-dual-write=NOT_APPLICABLE");
    }

    @Test
    void scenarioListFailsOnInvalidFiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("bad.yaml"), "schemaVersion: 9\n");

        assertThat(run("scenarios", "list", "--scenarios-dir", dir.toString())).isEqualTo(2);
        assertThat(out.toString()).contains("INVALID", "unsupported schemaVersion 9");
    }

    @Test
    void reportOfAnUnknownRunIsAUsageError(@TempDir Path runs) {
        assertThat(run("report", "--run", "20260101-000000-aaaaaa", "--runs-dir", runs.toString())).isEqualTo(2);
        assertThat(err.toString()).contains("no run 20260101-000000-aaaaaa");
    }

    @Test
    void cleanupRejectsMalformedRunIds() {
        assertThat(run("cleanup", "--run", "../../etc")).isEqualTo(2);
        assertThat(err.toString()).contains("is not a run id");
    }

    @Test
    void regeneratesTheReportOfASavedRun(@TempDir Path runs) throws Exception {
        String runId = "20260101-000000-bbbbbb";
        Path dir = runs.resolve(runId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), "{\"runId\":\"" + runId + "\"}");
        ReportFiles.write(dir, "run", runId, List.of(result(runId)));
        Files.delete(dir.resolve("report.html"));

        assertThat(run("report", "--run", runId, "--runs-dir", runs.toString())).isZero();

        assertThat(dir.resolve("report.html")).exists();
        assertThat(out.toString()).contains("happy-path / outbox-idempotent: CONSISTENT");
    }

    @Test
    void exitCodesFollowTheContract() {
        assertThat(Console.exitCodeForRun(Outcome.CONSISTENT)).isZero();
        assertThat(Console.exitCodeForRun(Outcome.VIOLATION_OBSERVED)).isEqualTo(1);
        assertThat(Console.exitCodeForRun(Outcome.INCONCLUSIVE)).isEqualTo(2);

        RunResult ok = result("20260101-000000-cccccc");
        RunResult mismatch = withExpectation(ok, Expectation.NOT_MATCHED);
        RunResult inconclusive = withExpectation(ok, Expectation.NOT_VERIFIED);
        assertThat(Console.exitCodeForMatrix(List.of(ok, ok))).isZero();
        assertThat(Console.exitCodeForMatrix(List.of(ok, mismatch))).isEqualTo(1);
        assertThat(Console.exitCodeForMatrix(List.of(mismatch, inconclusive))).isEqualTo(2);
    }

    private static RunResult withExpectation(RunResult r, Expectation e) {
        return new RunResult(r.runId(), r.parentRunId(), r.scenario(), r.strategy(), r.startedAt(), r.finishedAt(),
                r.observation(), r.outcome(), r.outcomeReasons(), r.expected(), e, r.invariants(), r.measurements(),
                r.fault(), r.timeline(), r.environment(), r.obstacles());
    }

    /** A consistent result computed from an empty-but-valid snapshot of a one-order workload. */
    private static RunResult result(String runId) {
        Workload w = new Workload(1, 1, 1, 10, 0, 1);
        WorkloadPlan plan = WorkloadPlan.from(w);
        var o = plan.orders().get(0);
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        LabSnapshot s = new LabSnapshot(
                new ProducerSnapshot(List.of(new com.example.commitgap.core.snapshot.OrderRow(o.orderId(), o.eventId(),
                        o.sku(), 1, t)), List.of(), List.of()),
                new ConsumerSnapshot(9, List.of(new com.example.commitgap.core.snapshot.StockMovement(1, o.eventId(),
                        o.orderId(), o.sku(), -1, t)), List.of(new com.example.commitgap.core.snapshot.ProcessedMessage(
                        "stock-consumer", o.eventId(), t)), List.of()),
                new BrokerSnapshot(true, 0, 0, 0, -1, -1, -1, null), t);
        var invariants = new InvariantEvaluator(plan, Strategy.OUTBOX_IDEMPOTENT, s).evaluate(List.of(InvariantId.values()));
        return new RunResult(runId, null, new RunResult.ScenarioSummary("happy-path", "No fault.", "happy-path.yaml", w,
                "none", "none", List.of()), "outbox-idempotent", t, t.plusSeconds(1),
                new RunResult.ObservationSummary(30, 1000, "settled"), Outcome.CONSISTENT, List.of("all held"),
                Outcome.CONSISTENT, Expectation.MATCHED, invariants, Measurements.of(plan, Strategy.OUTBOX_IDEMPOTENT, s),
                FaultExecution.notRequired(), List.of(), new EnvironmentInfo("test", "java", "os", "docker",
                "commitgap-" + runId, Map.of(), Map.of(), "x", Map.of(), List.of()), List.of());
    }
}
