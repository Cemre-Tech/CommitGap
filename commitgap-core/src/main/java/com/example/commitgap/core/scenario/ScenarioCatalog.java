package com.example.commitgap.core.scenario;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Finds scenario files in a directory and resolves a scenario by id or by path. */
public final class ScenarioCatalog {

    private final Path directory;
    private final ScenarioLoader loader;

    public ScenarioCatalog(Path directory, ScenarioLoader loader) {
        this.directory = directory;
        this.loader = loader;
    }

    public Path directory() {
        return directory;
    }

    /** All scenario files in the directory, sorted by file name. Invalid files are reported, not skipped. */
    public List<Path> files() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Resolves {@code reference} as a path to a YAML file when it looks like one, otherwise as a
     * scenario id in the catalog directory.
     */
    public Scenario resolve(String reference) {
        Path asPath = Path.of(reference);
        if (reference.endsWith(".yaml") || reference.endsWith(".yml")) {
            if (!Files.isRegularFile(asPath)) {
                throw new ScenarioValidationException(reference, List.of("file not found"));
            }
            return loader.load(asPath);
        }
        Path candidate = directory.resolve(reference + ".yaml");
        if (!Files.isRegularFile(candidate)) {
            throw new ScenarioValidationException(reference, List.of("no scenario with this id in " + directory
                    + " (run 'commitgap scenarios list')"));
        }
        Scenario scenario = loader.load(candidate);
        if (!scenario.id().equals(reference)) {
            throw new ScenarioValidationException(candidate.getFileName().toString(),
                    List.of("file name and id differ: id is '" + scenario.id() + "'"));
        }
        return scenario;
    }
}
