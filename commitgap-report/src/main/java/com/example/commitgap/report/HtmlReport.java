package com.example.commitgap.report;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Renders a report JSON document as one self-contained HTML file: inline CSS, no scripts, no fonts or
 * assets from the network, no tracking. Every status is written out in words with a symbol; color only
 * repeats what the text already says.
 */
public final class HtmlReport {

    private HtmlReport() {
    }

    public static String render(JsonNode report) {
        StringBuilder h = new StringBuilder(64 * 1024);
        String kind = report.path("kind").asString();
        String runId = report.path("runId").asString();
        h.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<link rel=\"icon\" href=\"data:,\">\n")
                .append("<title>CommitGap report ").append(esc(runId)).append("</title>\n<style>").append(CSS)
                .append("</style>\n</head>\n<body>\n<main>\n");

        h.append("<header><p class=\"brand\">CommitGap</p><h1>")
                .append(esc(title(report))).append("</h1>")
                .append("<p class=\"meta\">Run <code>").append(esc(runId)).append("</code> · ")
                .append(esc(kind)).append(" · generated ").append(esc(report.path("generatedAt").asString()))
                .append(" · CLI ").append(esc(report.path("cliVersion").asString()))
                .append(" · report schema v").append(report.path("reportSchemaVersion").asInt())
                .append(" · <a href=\"report.json\">raw JSON</a></p></header>\n");

        renderSummary(h, report);
        renderMatrix(h, report);

        h.append("<h2>Runs</h2>\n");
        for (JsonNode run : report.path("runs")) {
            renderRun(h, run, report.path("runs").size() == 1);
        }

        h.append("<footer><p>Business results come only from the producer and consumer database snapshots taken at ")
                .append("the end of each observation window. Logs and the timeline explain them; they are not evidence. ")
                .append("Checks that held in one run are not a general exactly-once guarantee for every failure.</p>")
                .append("<p>An open-source project by [COMPANY_NAME].</p></footer>\n");
        h.append("</main>\n</body>\n</html>\n");
        return h.toString();
    }

    private static String title(JsonNode report) {
        JsonNode runs = report.path("runs");
        if (runs.size() == 1) {
            JsonNode r = runs.get(0);
            return r.path("scenario").path("id").asString() + " · " + r.path("strategy").asString();
        }
        Set<String> scenarios = new LinkedHashSet<>();
        report.path("matrix").forEach(c -> scenarios.add(c.path("scenario").asString()));
        return scenarios.size() == 1 ? "Strategy comparison: " + scenarios.iterator().next()
                : "Strategy and scenario comparison";
    }

    private static void renderSummary(StringBuilder h, JsonNode report) {
        JsonNode s = report.path("summary");
        JsonNode o = s.path("outcomes");
        h.append("<section class=\"summary\" aria-label=\"Summary\">")
                .append(stat("Cells", s.path("cells").asLong()))
                .append(stat("Expectation matched", s.path("expectationMatched").asLong()))
                .append(stat("Expectation not matched", s.path("expectationNotMatched").asLong()))
                .append(stat("Not verified (inconclusive)", s.path("notVerified").asLong()))
                .append(stat("Consistent", o.path("CONSISTENT").asLong()))
                .append(stat("Violation observed", o.path("VIOLATION_OBSERVED").asLong()))
                .append(stat("Not applicable", o.path("NOT_APPLICABLE").asLong()))
                .append("</section>\n");
        h.append("<p class=\"note\"><strong>Two separate questions.</strong> The <em>outcome</em> says whether the ")
                .append("business data stayed correct. The <em>expectation</em> says whether the demonstration behaved ")
                .append("as the scenario predicts. A fragile strategy that shows its predicted violation matches the ")
                .append("expectation and still has the outcome VIOLATION_OBSERVED.</p>\n");
    }

    private static String stat(String label, long value) {
        return "<div class=\"stat\"><span class=\"value\">" + value + "</span><span class=\"label\">" + esc(label)
                + "</span></div>";
    }

    private static void renderMatrix(StringBuilder h, JsonNode report) {
        List<String> scenarios = new ArrayList<>();
        List<String> strategies = List.of("naive-dual-write", "transactional-outbox", "outbox-idempotent");
        report.path("matrix").forEach(c -> {
            if (!scenarios.contains(c.path("scenario").asString())) {
                scenarios.add(c.path("scenario").asString());
            }
        });
        h.append("<h2>Strategy comparison</h2>\n<div class=\"scroll\"><table class=\"matrix\">\n<thead><tr><th scope=\"col\">Scenario</th>");
        strategies.forEach(s -> h.append("<th scope=\"col\">").append(esc(s)).append("</th>"));
        h.append("</tr></thead>\n<tbody>\n");
        for (String scenario : scenarios) {
            h.append("<tr><th scope=\"row\">").append(esc(scenario)).append("</th>");
            for (String strategy : strategies) {
                JsonNode cell = null;
                for (JsonNode c : report.path("matrix")) {
                    if (scenario.equals(c.path("scenario").asString()) && strategy.equals(c.path("strategy").asString())) {
                        cell = c;
                    }
                }
                if (cell == null) {
                    h.append("<td class=\"empty\">not run</td>");
                    continue;
                }
                h.append("<td>").append(outcomeBadge(cell.path("outcome").asString()))
                        .append("<div class=\"expect\">").append(expectationText(cell.path("expectation").asString(),
                                cell.path("expected").asString()))
                        .append("</div><a class=\"jump\" href=\"#run-").append(esc(cell.path("runId").asString()))
                        .append("\">details</a></td>");
            }
            h.append("</tr>\n");
        }
        h.append("</tbody></table></div>\n");
    }

    private static void renderRun(StringBuilder h, JsonNode run, boolean open) {
        String runId = run.path("runId").asString();
        JsonNode scenario = run.path("scenario");
        JsonNode result = run.path("result");
        h.append("<details class=\"run\" id=\"run-").append(esc(runId)).append("\"").append(open ? " open" : "").append(">")
                .append("<summary><span class=\"run-title\">").append(esc(scenario.path("id").asString())).append(" · ")
                .append(esc(run.path("strategy").asString())).append("</span> ")
                .append(outcomeBadge(result.path("outcome").asString())).append(' ')
                .append("<span class=\"expect\">").append(expectationText(result.path("expectation").asString(),
                        result.path("expected").asString())).append("</span></summary>\n");

        h.append("<p>").append(esc(scenario.path("description").asString())).append("</p>\n");
        h.append("<dl class=\"facts\">")
                .append(fact("Run id", "<code>" + esc(runId) + "</code>"))
                .append(fact("Fault", esc(scenario.path("fault").asString())))
                .append(fact("Recovery", esc(scenario.path("recovery").asString())))
                .append(fact("Workload", esc(workload(scenario.path("workload")))))
                .append(fact("Observation", esc(run.path("observation").path("endReason").asString() + " after "
                        + run.path("observation").path("observedMillis").asLong() + " ms (window "
                        + run.path("observation").path("timeoutSeconds").asInt() + " s)")))
                .append("</dl>\n");

        h.append("<h3>Why this outcome</h3><ul>");
        result.path("reasons").forEach(r -> h.append("<li>").append(esc(r.asString())).append("</li>"));
        h.append("</ul>\n");
        if (!result.path("obstacles").isEmpty()) {
            h.append("<div class=\"callout\" role=\"note\"><strong>Measurement obstacles.</strong> These prevented ")
                    .append("a reliable measurement; the run is not evidence of data loss or of correctness.<ul>");
            result.path("obstacles").forEach(o -> h.append("<li>").append(esc(o.asString())).append("</li>"));
            h.append("</ul></div>\n");
        }

        renderFault(h, run.path("fault"));
        renderInvariants(h, run.path("invariants"));
        renderMeasurements(h, run.path("measurements"));
        renderTimeline(h, run.path("timeline"));
        renderEnvironment(h, run.path("environment"));
        h.append("</details>\n");
    }

    private static void renderFault(StringBuilder h, JsonNode f) {
        h.append("<h3>Fault</h3><dl class=\"facts\">")
                .append(fact("Status", esc(f.path("status").asString())))
                .append(fact("Description", esc(f.path("description").asString(""))));
        if (!f.path("checkpoint").isNull() && !f.path("checkpoint").isMissingNode()) {
            h.append(fact("Checkpoint", "<code>" + esc(f.path("checkpoint").asString()) + "</code> reached at "
                    + esc(f.path("checkpointReachedAt").asString("-"))));
        }
        if (!f.path("checkpointContext").isEmpty()) {
            StringBuilder ctx = new StringBuilder();
            f.path("checkpointContext").properties().forEach(e -> ctx.append(esc(e.getKey())).append("=<code>")
                    .append(esc(e.getValue().asString())).append("</code> "));
            h.append(fact("Target state", ctx.toString()));
        }
        h.append(fact("Verification", esc(f.path("verification").asString("-"))));
        if (!f.path("problem").isNull() && !f.path("problem").isMissingNode()) {
            h.append(fact("Problem", esc(f.path("problem").asString())));
        }
        h.append("</dl>\n");
    }

    private static void renderInvariants(StringBuilder h, JsonNode invariants) {
        h.append("<h3>Invariants</h3><div class=\"scroll\"><table><thead><tr><th scope=\"col\">Check</th>")
                .append("<th scope=\"col\">Status</th><th scope=\"col\">Expected</th><th scope=\"col\">Measured</th>")
                .append("<th scope=\"col\">Explanation and evidence</th></tr></thead><tbody>");
        for (JsonNode i : invariants) {
            h.append("<tr><th scope=\"row\"><code>").append(esc(i.path("id").asString())).append("</code><div class=\"desc\">")
                    .append(esc(i.path("description").asString())).append("</div></th><td>")
                    .append(invariantBadge(i.path("status").asString())).append("</td><td>")
                    .append(esc(i.path("expected").asString())).append("</td><td>")
                    .append(esc(i.path("actual").asString())).append("</td><td>")
                    .append(esc(i.path("explanation").asString()));
            if (!i.path("evidence").isEmpty()) {
                h.append("<dl class=\"evidence\">");
                i.path("evidence").properties().forEach(e -> {
                    h.append("<dt>").append(esc(e.getKey())).append("</dt><dd>");
                    e.getValue().forEach(v -> h.append("<code>").append(esc(v.asString())).append("</code> "));
                    h.append("</dd>");
                });
                h.append("</dl>");
            }
            h.append("</td></tr>");
        }
        if (invariants.isEmpty()) {
            h.append("<tr><td colspan=\"5\">No invariant was evaluated (no snapshot, or not applicable).</td></tr>");
        }
        h.append("</tbody></table></div>\n");
    }

    private static final Map<String, String> MEASUREMENT_LABELS = new java.util.LinkedHashMap<>();

    static {
        MEASUREMENT_LABELS.put("plannedOrders", "Orders in the workload");
        MEASUREMENT_LABELS.put("committedOrders", "Orders committed (producer database)");
        MEASUREMENT_LABELS.put("uncommittedOrders", "Orders not committed (rolled back or never reached)");
        MEASUREMENT_LABELS.put("publishAttempts", "Publish attempts (publish log)");
        MEASUREMENT_LABELS.put("confirmedPublishes", "Publishes positively confirmed by the broker");
        MEASUREMENT_LABELS.put("brokerPublished", "Messages published, broker statistics (sampled)");
        MEASUREMENT_LABELS.put("brokerDeliveries", "Deliveries, broker statistics (sampled)");
        MEASUREMENT_LABELS.put("brokerRedeliveries", "Redeliveries, broker statistics (sampled)");
        MEASUREMENT_LABELS.put("consumerAttempts", "Consumer attempts (delivery log)");
        MEASUREMENT_LABELS.put("consumerRedeliveries", "Consumer attempts flagged as redelivered");
        MEASUREMENT_LABELS.put("businessEffects", "Business effects (stock movements)");
        MEASUREMENT_LABELS.put("eventsWithEffect", "Distinct events with a business effect");
        MEASUREMENT_LABELS.put("duplicateEffects", "Duplicate business effects");
        MEASUREMENT_LABELS.put("committedOrdersWithoutEffect", "Committed orders without a business effect");
        MEASUREMENT_LABELS.put("outboxPending", "Outbox rows still pending");
        MEASUREMENT_LABELS.put("outboxParked", "Outbox rows parked after bounded retries");
        MEASUREMENT_LABELS.put("queueReady", "Messages ready in the queue");
        MEASUREMENT_LABELS.put("queueUnacknowledged", "Messages delivered but unacknowledged");
        MEASUREMENT_LABELS.put("deadLettered", "Messages dead-lettered");
        MEASUREMENT_LABELS.put("initialStock", "Initial stock");
        MEASUREMENT_LABELS.put("expectedStock", "Expected final stock");
        MEASUREMENT_LABELS.put("finalStock", "Measured final stock");
    }

    private static void renderMeasurements(StringBuilder h, JsonNode m) {
        h.append("<h3>Measurements</h3>");
        if (m == null || m.isNull() || m.isMissingNode()) {
            h.append("<p>No database snapshot was taken.</p>\n");
            return;
        }
        h.append("<div class=\"scroll\"><table class=\"compact\"><tbody>");
        MEASUREMENT_LABELS.forEach((key, label) -> {
            long v = m.path(key).asLong();
            h.append("<tr><th scope=\"row\">").append(esc(label)).append("</th><td class=\"num\">")
                    .append(v < 0 ? "<span class=\"muted\">not measured / not applicable</span>" : String.valueOf(v))
                    .append("</td></tr>");
        });
        h.append("</tbody></table></div>\n");
    }

    private static void renderTimeline(StringBuilder h, JsonNode timeline) {
        h.append("<h3>Timeline</h3><p class=\"muted\">Checkpoints, faults, restarts and deliveries. Runner entries are ")
                .append("recorded by CommitGap as they happen; process entries come from logs written outside the ")
                .append("business transactions.</p><div class=\"scroll\"><table class=\"timeline\"><thead><tr>")
                .append("<th scope=\"col\">+ms</th><th scope=\"col\">Source</th><th scope=\"col\">Kind</th>")
                .append("<th scope=\"col\">What happened</th></tr></thead><tbody>");
        for (JsonNode e : timeline) {
            String kind = e.path("kind").asString();
            boolean key = Set.of("checkpoint-reached", "kill-sent", "fault-applied", "recovered", "relay-barrier-opened",
                    "lab-error", "checkpoint-not-reached", "recovery-failed", "order-interrupted").contains(kind);
            h.append("<tr").append(key ? " class=\"key\"" : "").append("><td class=\"num\">")
                    .append(e.path("offsetMillis").asLong()).append("</td><td>").append(esc(e.path("source").asString()))
                    .append("</td><td><code>").append(esc(kind)).append("</code></td><td>")
                    .append(esc(e.path("message").asString())).append("</td></tr>");
        }
        h.append("</tbody></table></div>\n");
    }

    private static void renderEnvironment(StringBuilder h, JsonNode env) {
        h.append("<h3>Environment</h3><dl class=\"facts\">")
                .append(fact("CommitGap", esc(env.path("cliVersion").asString()) + ", report schema v"
                        + env.path("reportSchemaVersion").asInt()))
                .append(fact("Runner Java", esc(env.path("javaVersion").asString())))
                .append(fact("OS", esc(env.path("os").asString())))
                .append(fact("Docker", esc(env.path("dockerServerVersion").asString())))
                .append(fact("Resource namespace", "<code>" + esc(env.path("resourceNamespace").asString()) + "</code>"));
        StringBuilder images = new StringBuilder();
        env.path("images").properties().forEach(e -> images.append(esc(e.getKey())).append(": <code>")
                .append(esc(e.getValue().asString())).append("</code> <span class=\"muted\">")
                .append(esc(shortId(env.path("imageIds").path(e.getKey()).asString("")))).append("</span><br>"));
        h.append(fact("Images", images.toString()));
        StringBuilder components = new StringBuilder();
        env.path("components").properties().forEach(e -> components.append(esc(e.getKey())).append(' ')
                .append(esc(e.getValue().asString())).append("<br>"));
        h.append(fact("Components", components.toString()))
                .append(fact("Demo artifact SHA-256", "<code>" + esc(env.path("demoArtifactSha256").asString()) + "</code>"))
                .append(fact("Credentials", esc(env.path("credentials").asString())));
        StringBuilder resources = new StringBuilder();
        env.path("resources").forEach(r -> resources.append(esc(r.asString())).append("<br>"));
        h.append(fact("Resources", resources.toString())).append("</dl>\n");
    }

    private static String shortId(String id) {
        return id.startsWith("sha256:") && id.length() > 19 ? id.substring(0, 19) : id;
    }

    private static String workload(JsonNode w) {
        return w.path("orders").asInt() + " orders × " + w.path("quantityPerOrder").asInt() + ", initial stock "
                + w.path("initialStock").asInt() + ", seed " + w.path("seed").asLong()
                + (w.path("rollbackEvery").asInt() > 0 ? ", every " + w.path("rollbackEvery").asInt() + "th order rolled back" : "")
                + ", " + w.path("consumerWorkers").asInt() + " consumer worker(s)";
    }

    private static String fact(String term, String html) {
        return "<div><dt>" + esc(term) + "</dt><dd>" + html + "</dd></div>";
    }

    static String outcomeBadge(String outcome) {
        return switch (outcome) {
            case "CONSISTENT" -> "<span class=\"badge ok\">✓ CONSISTENT</span>";
            case "VIOLATION_OBSERVED" -> "<span class=\"badge bad\">✗ VIOLATION OBSERVED</span>";
            case "INCONCLUSIVE" -> "<span class=\"badge warn\">? INCONCLUSIVE</span>";
            case "NOT_APPLICABLE" -> "<span class=\"badge na\">– NOT APPLICABLE</span>";
            default -> "<span class=\"badge\">" + esc(outcome) + "</span>";
        };
    }

    private static String invariantBadge(String status) {
        return switch (status) {
            case "PASS" -> "<span class=\"badge ok\">✓ PASS</span>";
            case "FAIL" -> "<span class=\"badge bad\">✗ FAIL</span>";
            case "INCONCLUSIVE" -> "<span class=\"badge warn\">? INCONCLUSIVE</span>";
            case "NOT_APPLICABLE" -> "<span class=\"badge na\">– N/A</span>";
            default -> esc(status);
        };
    }

    private static String expectationText(String expectation, String expected) {
        String exp = expected == null || expected.isEmpty() || "null".equals(expected) ? "none" : expected;
        return switch (expectation) {
            case "MATCHED" -> "expected " + esc(exp) + ": <strong>matched</strong>";
            case "NOT_MATCHED" -> "expected " + esc(exp) + ": <strong class=\"mismatch\">not matched</strong>";
            default -> "expected " + esc(exp) + ": <strong>not verified</strong> (inconclusive)";
        };
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static final String CSS = """
            :root{--bg:#fbfbfa;--fg:#1d1d1b;--muted:#5f5f5a;--line:#dddcd6;--panel:#ffffff;--code:#f1f0ec;
            --ok-bg:#e3f1e6;--ok-fg:#1d5b2b;--bad-bg:#f8e1df;--bad-fg:#8a1f17;--warn-bg:#fbf0d4;--warn-fg:#6b4a00;
            --na-bg:#ecebe6;--na-fg:#4a4a45;--accent:#2f5d8a}
            @media (prefers-color-scheme:dark){:root{--bg:#161615;--fg:#ecebe6;--muted:#a3a29b;--line:#34332f;
            --panel:#1e1e1c;--code:#2a2926;--ok-bg:#1d3a24;--ok-fg:#a8dcb3;--bad-bg:#45201c;--bad-fg:#f2b3ab;
            --warn-bg:#43360f;--warn-fg:#f0d488;--na-bg:#2c2b28;--na-fg:#c9c8c1;--accent:#8db7e0}}
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.55 system-ui,-apple-system,"Segoe UI",Roboto,
            "Helvetica Neue",Arial,sans-serif}
            main{max-width:1180px;margin:0 auto;padding:24px 16px 48px}
            a{color:var(--accent)}
            code{font-family:ui-monospace,SFMono-Regular,Consolas,"Liberation Mono",monospace;font-size:.86em;
            background:var(--code);padding:1px 4px;border-radius:3px;word-break:break-all}
            .brand{margin:0;font-weight:700;letter-spacing:.04em;text-transform:uppercase;font-size:12px;color:var(--muted)}
            h1{margin:4px 0 6px;font-size:26px;line-height:1.25}
            h2{margin:36px 0 12px;font-size:20px}
            h3{margin:24px 0 8px;font-size:16px}
            .meta,.muted{color:var(--muted)}
            .summary{display:flex;flex-wrap:wrap;gap:8px;margin:20px 0 8px}
            .stat{background:var(--panel);border:1px solid var(--line);border-radius:6px;padding:10px 14px;min-width:120px}
            .stat .value{display:block;font-size:22px;font-weight:700}
            .stat .label{display:block;font-size:12px;color:var(--muted)}
            .note{background:var(--panel);border-left:3px solid var(--accent);padding:10px 14px;margin:16px 0}
            .callout{background:var(--warn-bg);color:var(--warn-fg);padding:10px 14px;border-radius:6px;margin:12px 0}
            .scroll{overflow-x:auto}
            table{border-collapse:collapse;width:100%;background:var(--panel);border:1px solid var(--line)}
            th,td{text-align:left;vertical-align:top;padding:8px 10px;border-bottom:1px solid var(--line)}
            thead th{font-size:12px;text-transform:uppercase;letter-spacing:.03em;color:var(--muted)}
            td.num{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}
            table.compact{max-width:640px}
            .matrix td{min-width:190px}
            .badge{display:inline-block;padding:2px 8px;border-radius:4px;font-weight:600;font-size:13px;white-space:nowrap}
            .ok{background:var(--ok-bg);color:var(--ok-fg)}.bad{background:var(--bad-bg);color:var(--bad-fg)}
            .warn{background:var(--warn-bg);color:var(--warn-fg)}.na{background:var(--na-bg);color:var(--na-fg)}
            .expect{font-size:13px;color:var(--muted);margin-top:4px}
            .mismatch{color:var(--bad-fg)}
            .jump{font-size:12px}
            .empty{color:var(--muted)}
            details.run{background:var(--panel);border:1px solid var(--line);border-radius:6px;padding:4px 16px 12px;margin:12px 0}
            details.run>summary{cursor:pointer;padding:10px 0;font-weight:600;display:flex;flex-wrap:wrap;gap:8px;align-items:center}
            .run-title{font-size:16px}
            dl.facts{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:6px 20px;margin:8px 0}
            dl.facts dt{font-size:12px;color:var(--muted);text-transform:uppercase;letter-spacing:.03em}
            dl.facts dd{margin:0 0 6px}
            .desc{font-weight:400;font-size:12px;color:var(--muted);margin-top:4px;max-width:280px}
            dl.evidence{margin:6px 0 0;font-size:13px}dl.evidence dt{color:var(--muted)}dl.evidence dd{margin:0 0 4px}
            tr.key td{font-weight:600}
            table.timeline td:nth-child(2),table.timeline td:nth-child(3){white-space:nowrap}
            footer{margin-top:40px;color:var(--muted);font-size:13px;border-top:1px solid var(--line);padding-top:12px}
            """;
}
