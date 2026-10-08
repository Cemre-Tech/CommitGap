package com.example.commitgap.core.scenario;

import java.util.List;

/** Thrown when a scenario file cannot be used. Carries every problem found, not just the first. */
public class ScenarioValidationException extends RuntimeException {

    private final String source;
    private final List<String> problems;

    public ScenarioValidationException(String source, List<String> problems) {
        super(format(source, problems));
        this.source = source;
        this.problems = List.copyOf(problems);
    }

    public String source() {
        return source;
    }

    public List<String> problems() {
        return problems;
    }

    private static String format(String source, List<String> problems) {
        StringBuilder sb = new StringBuilder("Invalid scenario ").append(source).append(':');
        for (String p : problems) {
            sb.append(System.lineSeparator()).append("  - ").append(p);
        }
        return sb.toString();
    }
}
