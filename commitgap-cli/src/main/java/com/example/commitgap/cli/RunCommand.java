package com.example.commitgap.cli;

import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.result.RunResult;
import com.example.commitgap.core.scenario.Scenario;
import com.example.commitgap.report.ReportFiles;
import com.example.commitgap.runtime.LabRunner;
import com.example.commitgap.runtime.RunIds;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "run", description = "Run one scenario against one strategy in its own environment.")
final class RunCommand implements Callable<Integer> {

    @Mixin
    LabOptions lab;

    @Mixin
    KeepOptions keep;

    @Option(names = "--scenario", required = true, paramLabel = "ID|FILE",
            description = "Scenario id from the scenarios directory, or a path to a .yaml file.")
    String scenario;

    @Option(names = "--strategy", required = true, paramLabel = "STRATEGY",
            description = "naive-dual-write, transactional-outbox or outbox-idempotent.")
    String strategy;

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        Strategy s = Strategy.fromId(strategy).orElseThrow(() -> new CommandLine.ParameterException(spec.commandLine(),
                "unknown strategy '" + strategy + "' (known: " + Strategy.knownIds() + ")"));
        Scenario sc = lab.catalog().resolve(scenario);
        if (!sc.isApplicableTo(s)) {
            err.println("commitgap: scenario '" + sc.id() + "' does not apply to " + s.id() + ": its fault targets the "
                    + sc.fault().orElseThrow().target().id() + ", which this strategy does not have. "
                    + "Use 'commitgap compare --scenario " + sc.id() + "' to see it marked NOT_APPLICABLE.");
            return CommitGapCli.USAGE_OR_OBSTACLE;
        }
        LabRunner runner = lab.runner(keep.policy(), out);
        String runId = RunIds.next();
        out.println("CommitGap run " + runId + ": " + sc.id() + " / " + s.id());
        out.flush();
        RunResult result = runner.run(sc, s, runId, null);
        Path dir = runner.store().directoryFor(runId, null);
        ReportFiles.write(dir, "run", runId, List.of(result));
        Console.printRun(out, result, dir);
        KeptResources.print(out, runner.store(), List.of(dir), runId);
        return Console.exitCodeForRun(result.outcome());
    }
}
