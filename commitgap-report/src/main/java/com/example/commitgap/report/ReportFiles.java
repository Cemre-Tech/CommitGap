package com.example.commitgap.report;

import com.example.commitgap.core.result.RunResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Writes {@code report.json} and {@code report.html} into a run directory, and regenerates HTML from saved JSON. */
public final class ReportFiles {

    public static final String JSON = "report.json";
    public static final String HTML = "report.html";

    private ReportFiles() {
    }

    public record Written(Path json, Path html) {
    }

    public static Written write(Path directory, String kind, String runId, List<RunResult> runs) {
        ObjectNode report = ReportJson.build(kind, runId, runs, Instant.now());
        return write(directory, report);
    }

    public static Written write(Path directory, JsonNode report) {
        Path json = directory.resolve(JSON);
        Path html = directory.resolve(HTML);
        atomicWrite(json, ReportJson.MAPPER.writeValueAsString(report));
        atomicWrite(html, HtmlReport.render(report));
        return new Written(json, html);
    }

    /** Reads a saved report and renders its HTML again. The JSON is the source of truth and is not modified. */
    public static Written regenerate(Path directory) {
        JsonNode report = read(directory);
        Path html = directory.resolve(HTML);
        atomicWrite(html, HtmlReport.render(report));
        return new Written(directory.resolve(JSON), html);
    }

    public static JsonNode read(Path directory) {
        Path json = directory.resolve(JSON);
        if (!Files.isRegularFile(json)) {
            throw new IllegalArgumentException("no " + JSON + " in " + directory);
        }
        JsonNode report;
        try {
            report = ReportJson.MAPPER.readTree(Files.readString(json, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + json, e);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(json + " is not valid JSON: " + e.getOriginalMessage());
        }
        int version = report.path("reportSchemaVersion").asInt(-1);
        if (version != ReportJson.SCHEMA_VERSION) {
            throw new IllegalArgumentException(json + " has report schema version " + version + "; this CommitGap "
                    + "version reads version " + ReportJson.SCHEMA_VERSION);
        }
        return report;
    }

    private static void atomicWrite(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
