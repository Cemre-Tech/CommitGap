package com.example.commitgap.cli;

import com.example.commitgap.report.ReportFiles;
import com.example.commitgap.runtime.RunIds;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;

@Command(name = "report",
        description = {"Regenerate the HTML report of a saved run from its report.json.",
                "The JSON is the record of the run and is not changed; nothing is re-measured."})
final class ReportCommand implements Callable<Integer> {

    @Mixin
    LabOptions lab;

    @Option(names = "--run", required = true, paramLabel = "RUN_ID", description = "A run, compare or demo id.")
    String runId;

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        if (!RunIds.isValid(runId)) {
            err.println("commitgap: '" + runId + "' is not a run id");
            return CommitGapCli.USAGE_OR_OBSTACLE;
        }
        Optional<Path> dir = lab.store().locate(runId);
        if (dir.isEmpty()) {
            err.println("commitgap: no run " + runId + " under " + lab.runsDirectory());
            return CommitGapCli.USAGE_OR_OBSTACLE;
        }
        ReportFiles.Written written = ReportFiles.regenerate(dir.get());
        JsonNode report = ReportFiles.read(dir.get());
        out.println("Report of " + report.path("kind").asString() + " " + runId + " (generated "
                + report.path("generatedAt").asString() + ")");
        for (JsonNode cell : report.path("matrix")) {
            out.println("  " + cell.path("scenario").asString() + " / " + cell.path("strategy").asString() + ": "
                    + cell.path("outcome").asString() + " (expected " + cell.path("expected").asString() + ", "
                    + cell.path("expectation").asString().toLowerCase().replace('_', ' ') + ")");
        }
        out.println("HTML        " + written.html());
        out.println("JSON        " + written.json());
        return CommitGapCli.OK;
    }
}
