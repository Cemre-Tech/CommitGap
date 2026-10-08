package com.example.commitgap.cli;

import com.example.commitgap.core.CommitGapVersion;
import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.result.RunResult;
import com.example.commitgap.core.scenario.Scenario;
import com.example.commitgap.report.ReportFiles;
import com.example.commitgap.runtime.LabRunner;
import com.example.commitgap.runtime.RunIds;
import com.example.commitgap.runtime.RunManifest;
import com.example.commitgap.runtime.RunStore;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs several scenarios against every strategy. Each cell gets its own environment (network,
 * containers, databases), started from scratch, with the same workload and seed.
 */
final class MatrixExecution {

    private MatrixExecution() {
    }

    static int run(String kind, List<Scenario> scenarios, LabOptions lab, KeepOptions keep, PrintWriter out) {
        LabRunner runner = lab.runner(keep.policy(), out);
        RunStore store = runner.store();
        String parentId = RunIds.next();
        Path parentDir = store.create(parentId, null);
        List<String> strategyIds = Arrays.stream(Strategy.values()).map(Strategy::id).toList();
        RunManifest manifest = new RunManifest(RunManifest.VERSION, parentId, null, kind,
                scenarios.stream().map(Scenario::id).toList(), strategyIds, Instant.now(), null, "running", false,
                List.of(), List.of(), CommitGapVersion.version());
        store.writeManifest(parentDir, manifest);

        int total = scenarios.size() * Strategy.values().length;
        out.println("CommitGap " + kind + " " + parentId + ": " + scenarios.size() + " scenario(s) x "
                + Strategy.values().length + " strategies = " + total + " cells, each in its own environment");
        out.flush();

        List<RunResult> results = new ArrayList<>();
        List<String> children = new ArrayList<>();
        List<Path> childDirs = new ArrayList<>();
        int n = 0;
        for (Scenario scenario : scenarios) {
            for (Strategy strategy : Strategy.values()) {
                n++;
                String childId = RunIds.child(parentId, scenario.id() + "-" + strategy.id());
                out.println("[" + n + "/" + total + "] " + scenario.id() + " / " + strategy.id());
                out.flush();
                RunResult result = runner.run(scenario, strategy, childId, parentId);
                Path childDir = store.directoryFor(childId, parentId);
                ReportFiles.write(childDir, "run", childId, List.of(result));
                results.add(result);
                children.add(childId);
                childDirs.add(childDir);
                out.println("      -> " + result.outcome() + " (expected " + result.expected() + ": "
                        + Console.expectationText(result.expectation()) + ")"
                        + (result.obstacles().isEmpty() ? "" : "  obstacle: " + String.join("; ", result.obstacles())));
                out.flush();
            }
        }

        ReportFiles.Written written = ReportFiles.write(parentDir, kind, parentId, results);
        store.writeManifest(parentDir, manifest.withChildren(children)
                .withStatus("completed", Instant.now(), false, List.of()));
        Console.printMatrix(out, results);
        out.println("Report      " + written.html());
        out.println("            " + written.json());
        KeptResources.print(out, store, childDirs, parentId);
        return Console.exitCodeForMatrix(results);
    }
}
