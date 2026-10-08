package com.example.commitgap.cli;

import com.example.commitgap.runtime.RunManifest;
import com.example.commitgap.runtime.RunStore;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

/** Prints the Docker resources a run kept, so they are never left behind silently. */
final class KeptResources {

    private KeptResources() {
    }

    static void print(PrintWriter out, RunStore store, List<Path> runDirectories, String cleanupRunId) {
        boolean any = false;
        for (Path dir : runDirectories) {
            RunManifest m;
            try {
                m = store.readManifest(dir);
            } catch (RuntimeException e) {
                continue;
            }
            if (!m.resourcesKept()) {
                continue;
            }
            if (!any) {
                out.println();
                out.println("Kept for inspection (still running or stopped in Docker):");
                any = true;
            }
            out.println("  run " + m.runId());
            m.resources().forEach(r -> out.println("    " + r));
        }
        if (any) {
            out.println("Remove them with: commitgap cleanup --run " + cleanupRunId);
        }
    }
}
