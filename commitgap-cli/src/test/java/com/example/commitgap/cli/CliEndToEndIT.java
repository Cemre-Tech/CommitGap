package com.example.commitgap.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.commitgap.core.model.Outcome;
import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.result.Expectation;
import com.example.commitgap.core.result.RunResult;
import com.example.commitgap.runtime.LabRunner;
import com.example.commitgap.runtime.ResourceJanitor;
import com.example.commitgap.runtime.RunIds;
import com.example.commitgap.runtime.RuntimeSettings;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CliEndToEndIT {

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int cli(String... args) {
        CommandLine cmd = CommitGapCli.newCommandLine();
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        return cmd.execute(args);
    }

    @Test
    void runKeepReportAndCleanup(@TempDir Path runs) throws Exception {
        int exit = cli("run", "--scenario", "crash-after-commit", "--strategy", "naive-dual-write", "--keep",
                "--runs-dir", runs.toString(), "--demo-jar", LabSupport.DEMO_JAR.toString());

        assertThat(exit).as(out + "\n" + err).isEqualTo(1); // a measured violation
        Matcher m = Pattern.compile("CommitGap run (\\S+):").matcher(out.toString());
        assertThat(m.find()).isTrue();
        String runId = m.group(1);
        Path dir = runs.resolve(runId);
        JsonNode report = JsonMapper.builder().build().readTree(Files.readString(dir.resolve("report.json"),
                StandardCharsets.UTF_8));
        JsonNode run = report.path("runs").get(0);
        assertThat(run.path("result").path("outcome").asString()).isEqualTo("VIOLATION_OBSERVED");
        assertThat(run.path("result").path("expectation").asString()).isEqualTo("MATCHED");
        assertThat(run.path("environment").path("resourceNamespace").asString()).isEqualTo("commitgap-" + runId);
        assertThat(Files.readString(dir.resolve("report.json"))).doesNotContainIgnoringCase("password=")
                .doesNotContain("SPRING_DATASOURCE_PASSWORD");
        assertThat(out.toString()).contains("Kept for inspection", "commitgap cleanup --run " + runId);
        // network + postgres, rabbitmq, toxiproxy, producer, consumer (the naive strategy has no relay)
        assertThat(new ResourceJanitor().list(runId)).hasSize(6);

        Files.delete(dir.resolve("report.html"));
        assertThat(cli("report", "--run", runId, "--runs-dir", runs.toString())).isZero();
        assertThat(dir.resolve("report.html")).exists();

        assertThat(cli("cleanup", "--run", runId, "--runs-dir", runs.toString())).isZero();
        assertThat(new ResourceJanitor().list(runId)).isEmpty();
        assertThat(Files.readString(dir.resolve("manifest.json"))).contains("\"cleaned\"");
    }

    @Test
    void anEnvironmentThatCannotStartIsInconclusiveNotASuccessOrALoss(@TempDir Path tmp) throws Exception {
        Path brokenJar = tmp.resolve("not-a-jar.jar");
        Files.writeString(brokenJar, "this is not a Spring Boot application");
        LabRunner runner = new LabRunner(new RuntimeSettings(brokenJar, tmp.resolve("runs"), Duration.ofSeconds(30),
                RuntimeSettings.KeepPolicy.NEVER, RuntimeSettings.ProgressListener.silent()));
        String runId = RunIds.next();

        RunResult r = runner.run(LabSupport.CATALOG.resolve("crash-after-commit"), Strategy.NAIVE_DUAL_WRITE, runId, null);

        assertThat(r.outcome()).isEqualTo(Outcome.INCONCLUSIVE);
        assertThat(r.expectation()).isEqualTo(Expectation.NOT_VERIFIED);
        assertThat(r.obstacles()).anyMatch(o -> o.contains("demo processes did not start"));
        assertThat(r.invariants()).isEmpty();
        assertThat(r.measurements()).isNull();
        assertThat(new ResourceJanitor().list(runId)).as("failed environments are cleaned up too").isEmpty();
        // Docker/Testcontainers errors can carry the container environment; no credential may reach the record.
        String recorded = String.join("\n", r.obstacles()) + r.timeline() + r.fault()
                + Files.readString(tmp.resolve("runs").resolve(runId).resolve("timeline.jsonl"));
        assertThat(recorded).doesNotContainPattern("(?i)PASSWORD=(?!\\[redacted\\])");
        assertThat(recorded).doesNotContain("SPRING_DATASOURCE_PASSWORD=");
    }
}
