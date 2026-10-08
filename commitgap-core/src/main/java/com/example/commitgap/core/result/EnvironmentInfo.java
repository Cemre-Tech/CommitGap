package com.example.commitgap.core.result;

import java.util.List;
import java.util.Map;

/**
 * Conditions a run was executed under, recorded so that differing results can be explained.
 * Never contains credentials: database and broker passwords are generated per run and not stored.
 *
 * @param resourceNamespace prefix of every Docker resource of the run, and the value of its run label
 * @param images            logical name to pinned image reference
 * @param imageIds          logical name to the local image id actually used
 * @param components        library and tool versions, e.g. spring-boot, testcontainers
 * @param resources         Docker containers and networks created for the run
 */
public record EnvironmentInfo(
        String cliVersion,
        String javaVersion,
        String os,
        String dockerServerVersion,
        String resourceNamespace,
        Map<String, String> images,
        Map<String, String> imageIds,
        String demoArtifactSha256,
        Map<String, String> components,
        List<String> resources) {

    public EnvironmentInfo {
        images = Map.copyOf(images);
        imageIds = Map.copyOf(imageIds);
        components = Map.copyOf(components);
        resources = List.copyOf(resources);
    }
}
