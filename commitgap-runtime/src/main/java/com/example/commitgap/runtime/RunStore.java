package com.example.commitgap.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Layout of the runs directory:
 * <pre>
 * &lt;runs&gt;/&lt;run-id&gt;/manifest.json, report.json, report.html, timeline.jsonl, logs/
 * &lt;runs&gt;/&lt;parent-id&gt;/cells/&lt;child-id&gt;/...   (cells of compare and demo runs)
 * </pre>
 */
public final class RunStore {

    public static final String MANIFEST = "manifest.json";
    public static final String REPORT_JSON = "report.json";
    public static final String REPORT_HTML = "report.html";
    public static final String TIMELINE = "timeline.jsonl";

    private final Path root;

    public RunStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path directoryFor(String runId, String parentRunId) {
        requireValid(runId);
        if (parentRunId == null) {
            return root.resolve(runId);
        }
        requireValid(parentRunId);
        return root.resolve(parentRunId).resolve("cells").resolve(runId);
    }

    public Path create(String runId, String parentRunId) {
        Path dir = directoryFor(runId, parentRunId);
        try {
            Files.createDirectories(dir.resolve("logs"));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create run directory " + dir, e);
        }
        return dir;
    }

    /** Finds a run directory by id, whether it is a top-level run or a cell of a compare/demo run. */
    public Optional<Path> locate(String runId) {
        if (!RunIds.isValid(runId)) {
            return Optional.empty();
        }
        Path direct = root.resolve(runId);
        if (Files.isRegularFile(direct.resolve(MANIFEST))) {
            return Optional.of(direct);
        }
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> parents = Files.list(root)) {
            return parents.map(p -> p.resolve("cells").resolve(runId))
                    .filter(p -> Files.isRegularFile(p.resolve(MANIFEST)))
                    .findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeManifest(Path dir, RunManifest manifest) {
        write(dir.resolve(MANIFEST), Json.MAPPER.writeValueAsString(manifest));
    }

    public RunManifest readManifest(Path dir) {
        try {
            return Json.MAPPER.readValue(Files.readString(dir.resolve(MANIFEST), StandardCharsets.UTF_8),
                    RunManifest.class);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + dir.resolve(MANIFEST), e);
        }
    }

    public static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    private static void requireValid(String runId) {
        if (!RunIds.isValid(runId)) {
            throw new IllegalArgumentException("invalid run id '" + runId + "'");
        }
    }
}
