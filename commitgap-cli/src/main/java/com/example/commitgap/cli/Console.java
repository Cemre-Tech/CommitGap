package com.example.commitgap.cli;

import com.example.commitgap.core.invariant.InvariantStatus;
import com.example.commitgap.core.model.Outcome;
import com.example.commitgap.core.result.Expectation;
import com.example.commitgap.core.result.RunResult;
import com.example.commitgap.core.scenario.ScenarioValidationException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Plain-text output. ASCII only, so it reads the same in every terminal and code page. */
final class Console {

    private Console() {
    }

    static String expectationText(Expectation e) {
        return switch (e) {
            case MATCHED -> "matched";
            case NOT_MATCHED -> "NOT MATCHED";
            case NOT_VERIFIED -> "not verified";
        };
    }

    static void printRun(PrintWriter out, RunResult r, Path directory) {
        out.println();
        out.println("Run         " + r.runId() + "  (" + r.scenario().id() + " / " + r.strategy() + ")");
        out.println("Outcome     " + r.outcome() + "   [business data, measured from database snapshots]");
        r.outcomeReasons().forEach(reason -> out.println("            - " + reason));
        out.println("Expected    " + r.expected() + "   -> expectation " + expectationText(r.expectation()));
        out.println("Fault       " + r.fault().status()
                + (r.fault().verification() == null ? "" : " - " + r.fault().verification())
                + (r.fault().problem() == null ? "" : " - " + r.fault().problem()));
        if (!r.invariants().isEmpty()) {
            Map<InvariantStatus, Long> counts = new java.util.EnumMap<>(InvariantStatus.class);
            r.invariants().forEach(i -> counts.merge(i.status(), 1L, Long::sum));
            StringBuilder sb = new StringBuilder();
            counts.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ", ").append(v).append(' ').append(k.name().toLowerCase()));
            out.println("Invariants  " + r.invariants().size() + " checked: " + sb);
        }
        if (r.measurements() != null) {
            var m = r.measurements();
            out.println("Measured    committed " + m.committedOrders() + ", effects " + m.businessEffects()
                    + " (" + m.duplicateEffects() + " duplicate), committed without effect "
                    + m.committedOrdersWithoutEffect() + ", consumer attempts " + m.consumerAttempts()
                    + ", stock " + m.finalStock() + " (expected " + m.expectedStock() + ")");
        }
        if (!r.obstacles().isEmpty()) {
            out.println("Obstacles   (measurement problems, not results)");
            r.obstacles().forEach(o -> out.println("            - " + o));
        }
        out.println("Observed    " + r.observation().endReason() + " after " + r.observation().observedMillis() + " ms");
        if (directory != null) {
            out.println("Report      " + directory.resolve("report.html"));
            out.println("            " + directory.resolve("report.json"));
        }
    }

    static void printMatrix(PrintWriter out, List<RunResult> results) {
        List<String> scenarios = results.stream().map(r -> r.scenario().id()).distinct().toList();
        List<String> strategies = List.of("naive-dual-write", "transactional-outbox", "outbox-idempotent");
        int first = Math.max(20, scenarios.stream().mapToInt(String::length).max().orElse(0) + 2);
        int col = 34;
        out.println();
        StringBuilder header = new StringBuilder(pad("scenario", first));
        strategies.forEach(s -> header.append(pad(s, col)));
        out.println(header.toString().stripTrailing());
        out.println("-".repeat(first + col * strategies.size()));
        for (String scenario : scenarios) {
            StringBuilder row = new StringBuilder(pad(scenario, first));
            for (String strategy : strategies) {
                RunResult cell = results.stream()
                        .filter(r -> r.scenario().id().equals(scenario) && r.strategy().equals(strategy))
                        .findFirst().orElse(null);
                row.append(pad(cell == null ? "(not run)" : cell.outcome() + " [" + expectationText(cell.expectation()) + "]", col));
            }
            out.println(row.toString().stripTrailing());
        }
        out.println();
        out.println("Cells show the measured outcome and, in brackets, whether it matched the scenario's expectation.");
        out.println("A fragile strategy that shows its expected violation is 'matched' and still VIOLATION_OBSERVED.");
    }

    static String message(Throwable t) {
        if (t instanceof ScenarioValidationException e) {
            return e.getMessage();
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    static int exitCodeForRun(Outcome outcome) {
        return switch (outcome) {
            case CONSISTENT -> CommitGapCli.OK;
            case VIOLATION_OBSERVED -> CommitGapCli.FAILED;
            case INCONCLUSIVE, NOT_APPLICABLE -> CommitGapCli.USAGE_OR_OBSTACLE;
        };
    }

    /** 2 if any cell could not be measured, else 1 if any expectation failed, else 0. */
    static int exitCodeForMatrix(List<RunResult> results) {
        if (results.stream().anyMatch(r -> r.expectation() == Expectation.NOT_VERIFIED)) {
            return CommitGapCli.USAGE_OR_OBSTACLE;
        }
        if (results.stream().anyMatch(r -> r.expectation() == Expectation.NOT_MATCHED)) {
            return CommitGapCli.FAILED;
        }
        return CommitGapCli.OK;
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s + " " : s + " ".repeat(width - s.length());
    }
}
