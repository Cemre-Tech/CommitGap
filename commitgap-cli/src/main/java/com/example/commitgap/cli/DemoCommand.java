package com.example.commitgap.cli;

import com.example.commitgap.core.scenario.Scenario;
import com.example.commitgap.core.scenario.ScenarioValidationException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "demo",
        description = {"Run the strategy x scenario comparison in real containers and show measured outcomes",
                "next to each scenario's expectation. Runs every bundled scenario unless --scenario is given.",
                "Expect several minutes: every cell starts its own environment."})
final class DemoCommand implements Callable<Integer> {

    @Mixin
    LabOptions lab;

    @Mixin
    KeepOptions keep;

    @Option(names = "--scenario", paramLabel = "ID|FILE",
            description = "Limit the demo to these scenarios (repeatable).")
    List<String> scenarios;

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        List<Scenario> selected = new ArrayList<>();
        if (scenarios == null || scenarios.isEmpty()) {
            List<Path> files = lab.catalog().files();
            if (files.isEmpty()) {
                throw new IllegalStateException("no scenario files in " + lab.scenariosDirectory());
            }
            List<String> problems = new ArrayList<>();
            for (Path file : files) {
                try {
                    selected.add(new com.example.commitgap.core.scenario.ScenarioLoader().load(file));
                } catch (ScenarioValidationException e) {
                    problems.add(e.getMessage());
                }
            }
            if (!problems.isEmpty()) {
                throw new IllegalStateException(String.join(System.lineSeparator(), problems));
            }
        } else {
            for (String s : scenarios) {
                selected.add(lab.catalog().resolve(s));
            }
        }
        return MatrixExecution.run("demo", selected, lab, keep, spec.commandLine().getOut());
    }
}
