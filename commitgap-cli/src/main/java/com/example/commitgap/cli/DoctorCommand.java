package com.example.commitgap.cli;

import com.example.commitgap.core.scenario.ScenarioLoader;
import com.example.commitgap.core.scenario.ScenarioValidationException;
import com.example.commitgap.runtime.LabImages;
import com.github.dockerjava.api.model.Info;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.testcontainers.DockerClientFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Spec;

@Command(name = "doctor",
        description = {"Check that this machine can run the lab: Java, Docker, the built demo artifact, the scenario",
                "files, the runs directory and the pinned container images. Installs nothing.",
                "Exit code 0 when everything required is ready, 2 otherwise."})
final class DoctorCommand implements Callable<Integer> {

    @Mixin
    LabOptions lab;

    @Spec
    CommandLine.Model.CommandSpec spec;

    private PrintWriter out;
    private boolean ready = true;

    @Override
    public Integer call() {
        out = spec.commandLine().getOut();
        out.println("CommitGap doctor");
        out.println();

        int feature = Runtime.version().feature();
        check(feature >= 21, "Java", System.getProperty("java.vendor") + " " + System.getProperty("java.version")
                + (feature >= 21 ? "" : " - Java 21 or newer is required"));

        boolean docker = false;
        try {
            docker = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            // reported below
        }
        if (docker) {
            Info info = DockerClientFactory.instance().getInfo();
            boolean linux = "linux".equalsIgnoreCase(info.getOsType());
            check(linux, "Docker", "server " + info.getServerVersion() + ", " + info.getOperatingSystem() + " ("
                    + info.getOsType() + "/" + info.getArchitecture() + ")"
                    + (linux ? "" : " - the lab needs a Docker engine running Linux containers"));
        } else {
            check(false, "Docker", "not reachable. Start Docker Desktop or the Docker daemon; CommitGap does not install it.");
        }

        Path jar = lab.demoJarOrNull();
        check(jar != null, "Demo build", jar != null ? jar.toString()
                : "not found at " + lab.demoJarCandidates() + ". Build it with: ./mvnw -DskipTests package");

        Path scenarios = lab.scenariosDirectory();
        List<Path> files = lab.catalog().files();
        int invalid = 0;
        for (Path f : files) {
            try {
                new ScenarioLoader().load(f);
            } catch (ScenarioValidationException e) {
                invalid++;
            }
        }
        check(!files.isEmpty() && invalid == 0, "Scenarios", files.size() + " file(s) in " + scenarios
                + (invalid == 0 ? ", all valid" : ", " + invalid + " invalid (run 'commitgap scenarios list')"));

        Path runs = lab.runsDirectory();
        boolean writable;
        try {
            Files.createDirectories(runs);
            writable = Files.isWritable(runs);
        } catch (Exception e) {
            writable = false;
        }
        check(writable, "Runs directory", runs + (writable ? "" : " is not writable"));

        out.println();
        out.println("Pinned images (pulled from their public registries on first use if missing):");
        for (Map.Entry<String, String> image : LabImages.all().entrySet()) {
            String state = "unknown (Docker not reachable)";
            if (docker) {
                try {
                    DockerClientFactory.instance().client().inspectImageCmd(image.getValue()).exec();
                    state = "present locally";
                } catch (RuntimeException e) {
                    state = "not present yet; will be pulled on the first run";
                }
            }
            out.printf("  %-13s %-42s %s%n", image.getKey(), image.getValue(), state);
        }
        String ryuk = System.getenv("TESTCONTAINERS_RYUK_DISABLED");
        out.println();
        out.println("Resource cleanup: CommitGap removes each run's containers by label when the run ends"
                + ("true".equalsIgnoreCase(ryuk) ? " (Testcontainers reaper disabled by the launcher)." : "."));
        out.println();
        out.println(ready ? "Ready. Try: commitgap demo" : "Not ready. Fix the items marked FAIL above.");
        return ready ? CommitGapCli.OK : CommitGapCli.USAGE_OR_OBSTACLE;
    }

    private void check(boolean ok, String what, String detail) {
        out.printf("  [%s] %-15s %s%n", ok ? " OK " : "FAIL", what, detail);
        ready &= ok;
    }
}
