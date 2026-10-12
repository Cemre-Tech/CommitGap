package com.example.commitgap.report;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/** A reading guide derived from schema v1 facts, without predicting what a fault should do. */
final class EventStory {

    private static final Set<String> EVENT_RECORDS = Set.of("publish", "delivery", "business-effect");

    private EventStory() {
    }

    static void render(StringBuilder h, JsonNode run) {
        JsonNode fault = run.path("fault");
        String eventId = fault.path("checkpointContext").path("eventId").asString("");
        h.append("<section class=\"event-story\" aria-label=\"Event story\"><h3>Event story</h3>")
                .append("<p>Follow the recorded fault, then compare it with the database findings. ")
                .append("A matching scenario expectation does not mean the business data is correct.</p>");
        if (eventId.isEmpty()) {
            h.append("<p class=\"muted\">No event id was recorded at the fault checkpoint. ")
                    .append("The run facts below are not attributed to a particular event.</p>");
        } else {
            h.append("<p>Checkpoint event: <code>").append(HtmlReport.esc(eventId)).append("</code></p>");
        }

        h.append("<ol class=\"story-steps\">");
        step(h, "Checkpoint", present(fault.path("checkpointReachedAt"))
                ? text(fault, "checkpoint") + " reached at " + text(fault, "checkpointReachedAt")
                : "No reached checkpoint was recorded.");
        String action = "Status: " + text(fault, "status") + ".";
        if (present(fault.path("appliedAt"))) {
            action += " Action recorded at " + text(fault, "appliedAt") + ": " + text(fault, "description") + ".";
        } else {
            action += " No applied action timestamp was recorded.";
        }
        if (present(fault.path("verification"))) {
            action += " Verification: " + text(fault, "verification") + ".";
        }
        if (present(fault.path("problem"))) {
            action += " Problem: " + text(fault, "problem") + ".";
        }
        step(h, "Fault action", action);
        step(h, "Recovery", present(fault.path("recoveredAt"))
                ? text(fault, "recovery") + " recorded at " + text(fault, "recoveredAt")
                : "No completed recovery timestamp was recorded. Configured recovery: "
                        + text(run.path("scenario"), "recovery") + ".");
        JsonNode observation = run.path("observation");
        step(h, "Observation closed", text(observation, "endReason") + " after "
                + text(observation, "observedMillis") + " ms. Outcome: "
                + text(run.path("result"), "outcome") + ".");
        h.append("</ol>");

        if (!eventId.isEmpty()) {
            renderEventRecords(h, run.path("timeline"), eventId);
        }
        renderFindings(h, run);
        h.append("<p class=\"story-mechanism\"><strong>How this strategy handles the gap.</strong> ")
                .append(HtmlReport.esc(mechanism(run.path("strategy").asString(""))))
                .append(" These are design mechanisms; their effectiveness in this run is assessed by the ")
                .append("measured invariants below.</p></section>\n");
    }

    private static void renderEventRecords(StringBuilder h, JsonNode timeline, String eventId) {
        h.append("<h4>Records for the checkpoint event</h4><ul class=\"story-records\">");
        boolean any = false;
        boolean effect = false;
        for (JsonNode e : timeline) {
            String kind = e.path("kind").asString("");
            if (!EVENT_RECORDS.contains(kind)
                    || !eventId.equals(e.path("attributes").path("eventId").asString(""))) {
                continue;
            }
            any = true;
            effect |= "business-effect".equals(kind);
            h.append("<li><span class=\"muted\">").append(HtmlReport.esc(text(e, "at")))
                    .append("</span> <strong>").append(HtmlReport.esc(kind)).append("</strong>: ")
                    .append(HtmlReport.esc(text(e, "message"))).append("</li>");
        }
        if (!any) {
            h.append("<li>No publish, delivery or business-effect record is present for this event.</li>");
        } else if (!effect) {
            h.append("<li>No business-effect record is present for this event.</li>");
        }
        h.append("</ul><p class=\"muted\">Publish and delivery records describe attempts. ")
                .append("Business-effect records come from the stock movement snapshot. ")
                .append("An absent attempt record alone does not prove message loss.</p>");
    }

    private static void renderFindings(StringBuilder h, JsonNode run) {
        h.append("<h4>Database findings</h4>");
        if (!present(run.path("measurements"))) {
            h.append("<p>No database snapshot was taken. Business effects and affected event identities ")
                    .append("could not be measured.</p>");
            return;
        }
        boolean any = false;
        h.append("<ul class=\"story-findings\">");
        for (JsonNode invariant : run.path("invariants")) {
            if (invariant.path("evidence").isEmpty()) {
                continue;
            }
            any = true;
            h.append("<li><strong>").append(HtmlReport.esc(text(invariant, "id"))).append("</strong>: ")
                    .append(HtmlReport.esc(text(invariant, "explanation"))).append("<dl class=\"evidence\">");
            invariant.path("evidence").properties().forEach(entry -> {
                h.append("<dt>").append(HtmlReport.esc(entry.getKey())).append("</dt><dd>");
                for (JsonNode value : entry.getValue()) {
                    h.append("<code>").append(HtmlReport.esc(value.asString())).append("</code> ");
                }
                h.append("</dd>");
            });
            h.append("</dl></li>");
        }
        h.append("</ul>");
        if (!any) {
            h.append("<p>No event-specific finding was recorded by the evaluated invariants. ")
                    .append("See their statuses and measurements below; this does not establish that a ")
                    .append("particular event had exactly one effect.</p>");
        }
        if ("INCONCLUSIVE".equals(run.path("result").path("outcome").asString())) {
            h.append("<p class=\"callout\">The observation is inconclusive. Missing effects may still arrive ")
                    .append("or measurement obstacles may prevent a reliable result. These findings do not ")
                    .append("establish permanent loss or correctness.</p>");
        }
    }

    private static void step(StringBuilder h, String title, String detail) {
        h.append("<li><strong>").append(HtmlReport.esc(title)).append("</strong><p>")
                .append(HtmlReport.esc(detail)).append("</p></li>");
    }

    private static boolean present(JsonNode node) {
        return !node.isMissingNode() && !node.isNull() && (!node.isString() || !node.asString().isEmpty());
    }

    private static String text(JsonNode parent, String key) {
        JsonNode value = parent.path(key);
        return present(value) ? value.asString() : "not recorded";
    }

    private static String mechanism(String strategy) {
        return switch (strategy) {
            case "naive-dual-write" -> "The order commit and broker publish are separate operations. "
                    + "There is no durable outbox entry to retry a publish interrupted after the order commit, "
                    + "and the consumer does not deduplicate repeated deliveries.";
            case "transactional-outbox" -> "The order and its outbox entry commit in one database transaction. "
                    + "The relay can retry pending entries after restart. A publish whose confirmation or "
                    + "outbox update is interrupted can be retried; the consumer does not deduplicate repeats.";
            case "outbox-idempotent" -> "The order and its outbox entry commit in one database transaction. "
                    + "The relay retries pending entries. The consumer commits its unique event-id record and "
                    + "stock change in one transaction, allowing repeated deliveries to be skipped.";
            default -> "No built-in mechanism description is available for strategy '" + strategy
                    + "'. Use the adapter contract and measured findings to interpret its behavior.";
        };
    }
}
