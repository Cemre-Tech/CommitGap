package com.example.commitgap.cli;

import com.example.commitgap.core.scenario.ScenarioCatalog;
import com.example.commitgap.core.scenario.ScenarioLoader;
import com.example.commitgap.runtime.LabRunner;
import com.example.commitgap.runtime.RunStore;
import com.example.commitgap.runtime.RuntimeSettings;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import picocli.CommandLine.Option;

/**
 * Locations and settings shared by the commands. Defaults are resolved from the CommitGap home
 * directory, which the launchers pass as {@code -Dcommitgap.home}.
 */
final class LabOptions {

    @Option(names = "--runs-dir", paramLabel = "DIR",
            description = "Where run directories and reports are written (default: ./.commitgap/runs, or COMMITGAP_RUNS_DIR).")
    Path runsDir;

    @Option(names = "--scenarios-dir", paramLabel = "DIR",
            description = "Directory of scenario YAML files (default: <home>/scenarios).")
    Path scenariosDir;

    @Option(names = "--demo-jar", paramLabel = "FILE",
            description = "Runnable demo jar started in the lab containers (default: the build output).")
    Path demoJar;

    @Option(names = "--startup-timeout", paramLabel = "SECONDS", defaultValue = "180",
            description = "Deadline for each container to become healthy (default: ${DEFAULT-VALUE}).")
    int startupTimeoutSeconds;

    static Path home() {
        String home = System.getProperty("commitgap.home");
        return (home == null ? Path.of("") : Path.of(home)).toAbsolutePath().normalize();
    }

    Path runsDirectory() {
        if (runsDir != null) {
            return runsDir.toAbsolutePath().normalize();
        }
        String env = System.getenv("COMMITGAP_RUNS_DIR");
        return (env == null || env.isBlank() ? Path.of(".commitgap", "runs") : Path.of(env)).toAbsolutePath().normalize();
    }

    Path scenariosDirectory() {
        return scenariosDir != null ? scenariosDir.toAbsolutePath().normalize() : home().resolve("scenarios");
    }

    ScenarioCatalog catalog() {
        return new ScenarioCatalog(scenariosDirectory(), new ScenarioLoader());
    }

    RunStore store() {
        return new RunStore(runsDirectory());
    }

    /** The demo jar, or null when none of the candidate locations has one. */
    Path demoJarOrNull() {
        for (Path candidate : demoJarCandidates()) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    List<Path> demoJarCandidates() {
        if (demoJar != null) {
            return List.of(demoJar);
        }
        String env = System.getenv("COMMITGAP_DEMO_JAR");
        if (env != null && !env.isBlank()) {
            return List.of(Path.of(env));
        }
        String prop = System.getProperty("commitgap.demoJar");
        if (prop != null && !prop.isBlank()) {
            return List.of(Path.of(prop));
        }
        return List.of(home().resolve("commitgap-demo/target/commitgap-demo-exec.jar"),
                home().resolve("lib/commitgap-demo.jar"));
    }

    LabRunner runner(RuntimeSettings.KeepPolicy keep, PrintWriter out) {
        Path jar = demoJarOrNull();
        if (jar == null) {
            throw new IllegalStateException("the demo jar was not found (looked at " + demoJarCandidates()
                    + "). Build it with ./mvnw -DskipTests package, or run 'commitgap doctor'.");
        }
        if (startupTimeoutSeconds < 10 || startupTimeoutSeconds > 1800) {
            throw new IllegalArgumentException("--startup-timeout must be between 10 and 1800 seconds");
        }
        return new LabRunner(new RuntimeSettings(jar, runsDirectory(), Duration.ofSeconds(startupTimeoutSeconds), keep,
                (runId, message) -> {
                    out.println("  [" + runId + "] " + message);
                    out.flush();
                }));
    }
}
