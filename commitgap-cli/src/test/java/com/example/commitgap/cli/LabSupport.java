package com.example.commitgap.cli;

import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.result.RunResult;
import com.example.commitgap.core.scenario.Scenario;
import com.example.commitgap.core.scenario.ScenarioCatalog;
import com.example.commitgap.core.scenario.ScenarioLoader;
import com.example.commitgap.runtime.LabRunner;
import com.example.commitgap.runtime.RunIds;
import com.example.commitgap.runtime.RuntimeSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Shared setup for integration tests that start real labs. */
final class LabSupport {

    static final Path HOME = Path.of(System.getProperty("commitgap.home", "..")).toAbsolutePath().normalize();
    static final Path DEMO_JAR = Path.of(System.getProperty("commitgap.demoJar",
            HOME.resolve("commitgap-demo/target/commitgap-demo-exec.jar").toString()));
    static final Path RUNS = HOME.resolve("commitgap-cli/target/it-runs");
    static final ScenarioCatalog CATALOG = new ScenarioCatalog(HOME.resolve("scenarios"), new ScenarioLoader());

    private LabSupport() {
    }

    static LabRunner runner() {
        if (!Files.isRegularFile(DEMO_JAR)) {
            throw new IllegalStateException("demo jar missing: " + DEMO_JAR + " (build with ./mvnw package)");
        }
        return new LabRunner(new RuntimeSettings(DEMO_JAR, RUNS, Duration.ofSeconds(120),
                RuntimeSettings.KeepPolicy.NEVER,
                (runId, message) -> System.out.println("[" + runId + "] " + message)));
    }

    static RunResult run(String scenarioId, Strategy strategy) {
        Scenario scenario = CATALOG.resolve(scenarioId);
        return runner().run(scenario, strategy, RunIds.next(), null);
    }

    static String describe(RunResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.scenario().id()).append(" / ").append(r.strategy()).append(": ").append(r.outcome())
                .append(" expected ").append(r.expected()).append(" (").append(r.expectation()).append(")\n");
        r.outcomeReasons().forEach(x -> sb.append("  reason: ").append(x).append('\n'));
        r.obstacles().forEach(x -> sb.append("  obstacle: ").append(x).append('\n'));
        sb.append("  fault: ").append(r.fault().status()).append(' ').append(r.fault().verification())
                .append(' ').append(r.fault().problem()).append('\n');
        r.invariants().forEach(i -> sb.append("  ").append(i.id().id()).append(' ').append(i.status())
                .append(" expected=").append(i.expected()).append(" actual=").append(i.actual())
                .append(' ').append(i.evidence()).append('\n'));
        if (r.measurements() != null) {
            sb.append("  ").append(r.measurements()).append('\n');
        }
        r.timeline().stream().filter(e -> "runner".equals(e.source()))
                .forEach(e -> sb.append("  ").append(e.at()).append(' ').append(e.kind()).append(": ")
                        .append(e.message()).append('\n'));
        return sb.toString();
    }
}
