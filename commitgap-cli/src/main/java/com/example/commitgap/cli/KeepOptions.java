package com.example.commitgap.cli;

import com.example.commitgap.runtime.RuntimeSettings;
import picocli.CommandLine.Option;

final class KeepOptions {

    @Option(names = "--keep-on-failure",
            description = "Keep the Docker resources of a run whose outcome is inconclusive, whose expectation did not "
                    + "match, or that was interrupted, and list them for inspection.")
    boolean keepOnFailure;

    @Option(names = "--keep", description = "Always keep the Docker resources (remove them later with 'commitgap cleanup').")
    boolean keepAlways;

    RuntimeSettings.KeepPolicy policy() {
        if (keepAlways) {
            return RuntimeSettings.KeepPolicy.ALWAYS;
        }
        return keepOnFailure ? RuntimeSettings.KeepPolicy.ON_FAILURE : RuntimeSettings.KeepPolicy.NEVER;
    }
}
