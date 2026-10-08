package com.example.commitgap.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.commitgap.core.result.RunResult;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class ReportTest {

    private static final Schema SCHEMA = loadSchema();

    private static Schema loadSchema() {
        try (InputStream in = ReportJson.class.getResourceAsStream("report.schema.json")) {
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void singleRunReportMatchesTheSchema() {
        ObjectNode report = ReportJson.build("run", "20260101-100000-abc123",
                List.of(Fixtures.naiveCrash("Kill the producer.")), Fixtures.T0);

        List<Error> errors = SCHEMA.validate(report);

        assertThat(errors).isEmpty();
    }

    @Test
    void comparisonWithAnInconclusiveCellMatchesTheSchema() {
        ObjectNode report = ReportJson.build("compare", "20260101-100000-abc123",
                List.of(Fixtures.naiveCrash("Kill the producer."), Fixtures.environmentFailure()), Fixtures.T0);

        assertThat(SCHEMA.validate(report)).isEmpty();
        assertThat(report.path("summary").path("notVerified").asInt()).isEqualTo(1);
        assertThat(report.path("runs").get(1).path("measurements").isNull()).isTrue();
    }

    @Test
    void schemaRejectsUnknownOutcomes() {
        ObjectNode report = ReportJson.build("run", "20260101-100000-abc123",
                List.of(Fixtures.naiveCrash("Kill the producer.")), Fixtures.T0);
        ((ObjectNode) report.path("matrix").get(0)).put("outcome", "PROBABLY_FINE");

        assertThat(SCHEMA.validate(report)).isNotEmpty();
    }

    @Test
    void reportCarriesTheMeasuredEvidenceAndKeepsOutcomeApartFromExpectation() {
        RunResult run = Fixtures.naiveCrash("Kill the producer.");
        String lostEvent = run.fault().checkpointContext().get("eventId");

        JsonNode json = ReportJson.build("run", run.runId(), List.of(run), Fixtures.T0).path("runs").get(0);

        assertThat(json.path("result").path("outcome").asString()).isEqualTo("VIOLATION_OBSERVED");
        assertThat(json.path("result").path("expectation").asString()).isEqualTo("MATCHED");
        JsonNode committed = json.path("invariants").get(0);
        assertThat(committed.path("status").asString()).isEqualTo("FAIL");
        assertThat(committed.path("evidence").path("missingWithoutPendingRecord").get(0).asString()).isEqualTo(lostEvent);
        assertThat(json.path("measurements").path("committedOrdersWithoutEffect").asInt()).isEqualTo(1);
        assertThat(json.path("environment").path("credentials").asString()).contains("never recorded");
    }

    @Test
    void htmlStatesStatusesInWordsAndEscapesContent() {
        RunResult run = Fixtures.naiveCrash("<script>alert('x')</script> & more");

        String html = HtmlReport.render(ReportJson.build("run", run.runId(), List.of(run), Fixtures.T0));

        assertThat(html).contains("VIOLATION OBSERVED", "✗ FAIL", "matched", "href=\"report.json\"");
        assertThat(html).contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; more");
        assertThat(html).doesNotContain("<script");
        // Nothing is loaded from anywhere: the only <link> is an inline data: favicon.
        assertThat(html).doesNotContainPattern("(?i)<link[^>]+href=\"(?!data:)|src=|@import|url\\(");
        assertThat(html).contains(run.fault().checkpointContext().get("eventId"));
    }

    @Test
    void htmlMarksMeasurementObstaclesAsNotEvidence() {
        String html = HtmlReport.render(ReportJson.build("compare", "20260101-100000-abc123",
                List.of(Fixtures.environmentFailure()), Fixtures.T0));

        assertThat(html).contains("? INCONCLUSIVE", "not verified", "Measurement obstacles",
                "not evidence of data loss or of correctness", "Docker is not available");
    }

    @Test
    void regeneratesHtmlFromSavedJson(@TempDir Path dir) throws Exception {
        RunResult run = Fixtures.naiveCrash("Kill the producer.");
        ReportFiles.Written written = ReportFiles.write(dir, "run", run.runId(), List.of(run));
        String original = Files.readString(written.html(), StandardCharsets.UTF_8);
        Files.delete(written.html());

        ReportFiles.regenerate(dir);

        assertThat(Files.readString(dir.resolve(ReportFiles.HTML), StandardCharsets.UTF_8)).isEqualTo(original);
    }

    @Test
    void refusesReportsOfAnotherSchemaVersion(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(ReportFiles.JSON), "{\"reportSchemaVersion\": 2}");

        assertThatThrownBy(() -> ReportFiles.regenerate(dir)).hasMessageContaining("schema version 2");
    }
}
