package com.example.commitgap.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class EventStoryTest {

    @Test
    void predictedViolationKeepsTheAffectedIdentityAndActualFaultFacts() {
        var source = Fixtures.naiveCrash("Kill the producer.");
        ObjectNode report = ReportJson.build("run", source.runId(), List.of(source), Fixtures.T0);

        String story = story(report);

        assertThat(story).contains("Checkpoint event:", source.fault().checkpointContext().get("eventId"),
                "producer.after-db-commit-before-publish", "Status: APPLIED", "exit code 137",
                "restart recorded at", "Outcome: VIOLATION_OBSERVED", "missingEffectEventIds",
                "No publish, delivery or business-effect record is present for this event",
                "matching scenario expectation does not mean the business data is correct");
        assertThat(story).doesNotContain("exactly once", "successfully recovered", "message was lost");
    }

    @Test
    void missingSnapshotAndUnverifiedFaultNeverInventEffectsOrRecovery() {
        var source = Fixtures.environmentFailure();
        ObjectNode report = ReportJson.build("run", source.runId(), List.of(source), Fixtures.T0);

        assertThat(story(report)).contains("No event id was recorded", "No reached checkpoint was recorded",
                "No completed recovery timestamp was recorded", "No database snapshot was taken",
                "Status: NOT_VERIFIED", "Outcome: INCONCLUSIVE")
                .doesNotContain("stock changed once", "restart recorded at", "0 business effects");
    }

    @Test
    void onlyTheCheckpointEventsOwnRecordsAreAttributedToIt() {
        ObjectNode report = report();
        ObjectNode run = (ObjectNode) report.path("runs").get(0);
        String id = run.path("fault").path("checkpointContext").path("eventId").asString();
        addRecord(run, "publish", id, "confirmed at broker");
        addRecord(run, "delivery", id, "skipped duplicate");
        addRecord(run, "business-effect", id, "stock -1");
        addRecord(run, "publish", "different-event", "unrelated publish");
        addRecord(run, "business-effect", "different-event", "unrelated stock -9");

        assertThat(story(report)).contains("confirmed at broker", "skipped duplicate", "stock -1",
                "Business-effect records come from the stock movement snapshot")
                .doesNotContain("unrelated publish", "unrelated stock", "No business-effect record");
    }

    @Test
    void inconclusivePendingWorkIsReportedWithoutDeclaringPermanentLoss() {
        ObjectNode report = report();
        ObjectNode run = (ObjectNode) report.path("runs").get(0);
        ((ObjectNode) run.path("result")).put("outcome", "INCONCLUSIVE");
        ObjectNode invariant = (ObjectNode) run.path("invariants").get(0);
        invariant.put("explanation", "An outbox row is still pending; its event may arrive later.");
        invariant.put("status", "INCONCLUSIVE");
        ObjectNode evidence = invariant.putObject("evidence");
        evidence.putArray("missingWithPendingOutboxRow").add("pending-event");

        assertThat(story(report)).contains("missingWithPendingOutboxRow", "pending-event",
                "may arrive later", "The observation is inconclusive",
                "do not establish permanent loss or correctness");
    }

    @Test
    void unknownFaultAndStrategyKeepTheirRecordedFactsAndEscapeAllNewContent() {
        ObjectNode report = report();
        ObjectNode run = (ObjectNode) report.path("runs").get(0);
        run.put("strategy", "future-<adapter>");
        ((ObjectNode) run.path("scenario")).put("id", "new-fault");
        ObjectNode fault = (ObjectNode) run.path("fault");
        fault.put("checkpoint", "custom.<before>");
        fault.put("description", "<script>kill & retry</script>");
        ((ObjectNode) fault.path("checkpointContext")).put("eventId", "event-<one>");
        addRecord(run, "publish", "event-<one>", "broker said <unknown> & timed out");
        ((ObjectNode) run.path("invariants").get(0)).putObject("evidence")
                .putArray("custom-<evidence>").add("event-<one>");

        assertThat(story(report)).contains("future-&lt;adapter&gt;", "custom.&lt;before&gt;",
                "&lt;script&gt;kill &amp; retry&lt;/script&gt;", "event-&lt;one&gt;",
                "broker said &lt;unknown&gt; &amp; timed out", "custom-&lt;evidence&gt;",
                "No built-in mechanism description")
                .doesNotContain("<script>", "<adapter>", "<evidence>");
    }

    @Test
    void recoveryFailureIsVisibleEvenWhenTheFaultActionHasATimestamp() {
        ObjectNode report = report();
        ObjectNode run = (ObjectNode) report.path("runs").get(0);
        ObjectNode fault = (ObjectNode) run.path("fault");
        fault.put("status", "RECOVERY_FAILED");
        fault.putNull("recoveredAt");
        fault.put("problem", "container restart failed");

        assertThat(story(report)).contains("Status: RECOVERY_FAILED", "container restart failed",
                "No completed recovery timestamp was recorded").doesNotContain("restart recorded at");
    }

    @Test
    void mechanismDescriptionsDistinguishRetryFromDeduplication() {
        ObjectNode report = report();
        ObjectNode run = (ObjectNode) report.path("runs").get(0);
        run.put("strategy", "transactional-outbox");
        assertThat(story(report)).contains("relay can retry pending entries",
                "consumer does not deduplicate repeats");

        run.put("strategy", "outbox-idempotent");
        assertThat(story(report)).contains("unique event-id record", "stock change in one transaction",
                "effectiveness in this run is assessed by the measured invariants");
    }

    private static ObjectNode report() {
        var source = Fixtures.naiveCrash("Kill the producer.");
        return ReportJson.build("run", source.runId(), List.of(source), Fixtures.T0);
    }

    private static void addRecord(ObjectNode run, String kind, String eventId, String message) {
        var record = ((tools.jackson.databind.node.ArrayNode) run.path("timeline")).addObject();
        record.put("at", "2026-01-01T10:00:04Z");
        record.put("kind", kind);
        record.put("message", message);
        record.putObject("attributes").put("eventId", eventId);
    }

    private static String story(ObjectNode report) {
        String html = HtmlReport.render(report);
        int start = html.indexOf("<section class=\"event-story\"");
        return html.substring(start, html.indexOf("</section>", start));
    }
}
