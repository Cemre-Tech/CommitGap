package com.example.commitgap.cli;

import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.scenario.Scenario;
import com.example.commitgap.core.scenario.ScenarioLoader;
import com.example.commitgap.core.scenario.ScenarioValidationException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Spec;

@Command(name = "scenarios", description = "Work with scenario files.",
        subcommands = {ScenariosCommand.ListScenarios.class})
final class ScenariosCommand implements Runnable {

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "list", description = "List and validate the scenario files. Exit code 2 if any file is invalid.")
    static final class ListScenarios implements Callable<Integer> {

        @Mixin
        LabOptions lab;

        @Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() {
            PrintWriter out = spec.commandLine().getOut();
            List<Path> files = lab.catalog().files();
            out.println("Scenarios in " + lab.scenariosDirectory());
            if (files.isEmpty()) {
                out.println("  (none)");
                return CommitGapCli.USAGE_OR_OBSTACLE;
            }
            boolean invalid = false;
            ScenarioLoader loader = new ScenarioLoader();
            for (Path file : files) {
                try {
                    Scenario s = loader.load(file);
                    out.println();
                    out.println("  " + s.id());
                    out.println("    " + s.description());
                    out.println("    fault:    " + s.fault().map(f -> f.describe()).orElse("none")
                            + (s.fault().isPresent() ? "; recovery: " + s.recovery().action().id() : ""));
                    out.println("    workload: " + s.workload().orders() + " orders, seed " + s.workload().seed()
                            + ", observation up to " + s.observation().timeoutSeconds() + "s");
                    StringBuilder expected = new StringBuilder();
                    for (Strategy strategy : Strategy.values()) {
                        expected.append(expected.isEmpty() ? "" : ", ").append(strategy.id()).append('=')
                                .append(s.expectedFor(strategy));
                    }
                    out.println("    expected: " + expected);
                } catch (ScenarioValidationException e) {
                    invalid = true;
                    out.println();
                    out.println("  INVALID " + e.getMessage());
                }
            }
            return invalid ? CommitGapCli.USAGE_OR_OBSTACLE : CommitGapCli.OK;
        }
    }
}
