package com.example.commitgap.cli;

import com.example.commitgap.runtime.ResourceJanitor;
import com.example.commitgap.runtime.RunIds;
import com.example.commitgap.runtime.RunManifest;
import com.example.commitgap.runtime.RunStore;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.testcontainers.DockerClientFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "cleanup",
        description = {"Remove the Docker containers and network of one CommitGap run (and of its cells for a",
                "compare or demo run). Only resources labelled commitgap.managed=true with this run id are",
                "touched; nothing is pruned and no other database or container is accessed."})
final class CleanupCommand implements Callable<Integer> {

    @Mixin
    LabOptions lab;

    @Option(names = "--run", required = true, paramLabel = "RUN_ID", description = "The run to clean up.")
    String runId;

    @Option(names = "--delete-files", description = "Also delete the run directory with its reports and logs.")
    boolean deleteFiles;

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        if (!RunIds.isValid(runId)) {
            err.println("commitgap: '" + runId + "' is not a run id");
            return CommitGapCli.USAGE_OR_OBSTACLE;
        }
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            err.println("commitgap: Docker is not available; nothing was removed");
            return CommitGapCli.USAGE_OR_OBSTACLE;
        }
        ResourceJanitor.Removal removal = new ResourceJanitor().removeRun(runId);
        if (removal.removed().isEmpty() && removal.failed().isEmpty()) {
            out.println("No Docker resources are labelled with run " + runId + ".");
        }
        removal.removed().forEach(r -> out.println("removed " + r));
        removal.failed().forEach(r -> err.println("could not remove " + r));

        RunStore store = lab.store();
        Optional<Path> dir = store.locate(runId);
        if (dir.isPresent()) {
            markCleaned(store, dir.get());
            if (deleteFiles) {
                deleteTree(dir.get(), store.root());
                out.println("deleted " + dir.get());
            }
        } else if (deleteFiles) {
            out.println("No run directory for " + runId + " under " + store.root());
        }
        return removal.failed().isEmpty() ? CommitGapCli.OK : CommitGapCli.USAGE_OR_OBSTACLE;
    }

    private static void markCleaned(RunStore store, Path dir) {
        try {
            RunManifest m = store.readManifest(dir);
            store.writeManifest(dir, m.withStatus("cleaned", m.finishedAt() == null ? Instant.now() : m.finishedAt(),
                    false, List.of()));
            Path cells = dir.resolve("cells");
            if (Files.isDirectory(cells)) {
                try (Stream<Path> children = Files.list(cells)) {
                    children.filter(c -> Files.isRegularFile(c.resolve(RunStore.MANIFEST)))
                            .forEach(c -> markCleaned(store, c));
                }
            }
        } catch (IOException | RuntimeException e) {
            // the Docker cleanup already happened; a stale manifest is cosmetic
        }
    }

    /** Deletes a run directory, refusing anything outside the runs root. */
    private static void deleteTree(Path dir, Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = dir.toAbsolutePath().normalize();
        if (!target.startsWith(normalizedRoot) || target.equals(normalizedRoot)) {
            throw new IllegalStateException("refusing to delete " + target + " outside " + normalizedRoot);
        }
        try (Stream<Path> walk = Files.walk(target)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
