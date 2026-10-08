package com.example.commitgap.cli;

import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "compare",
        description = {"Run one scenario against every strategy, each in a separate environment with the same workload.",
                "Strategies the scenario cannot apply to are reported as NOT_APPLICABLE."})
final class CompareCommand implements Callable<Integer> {

    @Mixin
    LabOptions lab;

    @Mixin
    KeepOptions keep;

    @Option(names = "--scenario", required = true, paramLabel = "ID|FILE",
            description = "Scenario id from the scenarios directory, or a path to a .yaml file.")
    String scenario;

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        return MatrixExecution.run("compare", List.of(lab.catalog().resolve(scenario)), lab, keep,
                spec.commandLine().getOut());
    }
}
