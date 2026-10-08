package com.example.commitgap.runtime;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Network;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testcontainers.DockerClientFactory;

/**
 * Removes Docker resources of exactly one run, selected by the {@code commitgap.run} or
 * {@code commitgap.parent-run} label. It never prunes, never matches by name pattern, and never
 * touches resources without the {@code commitgap.managed=true} label.
 */
public final class ResourceJanitor {

    public record Removal(List<String> removed, List<String> failed) {
    }

    private final DockerClient docker;

    public ResourceJanitor() {
        this(DockerClientFactory.instance().client());
    }

    ResourceJanitor(DockerClient docker) {
        this.docker = docker;
    }

    /** Resources labelled with this run id, or with this id as their parent run. */
    public List<String> list(String runId) {
        List<String> names = new ArrayList<>();
        containers(runId).forEach(c -> names.add("container " + name(c)));
        networks(runId).forEach(n -> names.add("network " + n.getName()));
        return names;
    }

    public Removal removeRun(String runId) {
        if (!RunIds.isValid(runId)) {
            throw new IllegalArgumentException("invalid run id '" + runId + "'");
        }
        List<String> removed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Container c : containers(runId)) {
            try {
                docker.removeContainerCmd(c.getId()).withForce(true).withRemoveVolumes(true).exec();
                removed.add("container " + name(c));
            } catch (com.github.dockerjava.api.exception.NotFoundException e) {
                // already gone
            } catch (RuntimeException e) {
                failed.add("container " + name(c) + ": " + e.getMessage());
            }
        }
        for (Network n : networks(runId)) {
            try {
                docker.removeNetworkCmd(n.getId()).exec();
                removed.add("network " + n.getName());
            } catch (com.github.dockerjava.api.exception.NotFoundException e) {
                // already gone
            } catch (RuntimeException e) {
                failed.add("network " + n.getName() + ": " + e.getMessage());
            }
        }
        return new Removal(removed, failed);
    }

    private List<Container> containers(String runId) {
        Set<String> seen = new LinkedHashSet<>();
        List<Container> result = new ArrayList<>();
        for (String label : List.of(Labels.RUN, Labels.PARENT_RUN)) {
            for (Container c : docker.listContainersCmd().withShowAll(true)
                    .withLabelFilter(Map.of(Labels.MANAGED, "true", label, runId)).exec()) {
                if (seen.add(c.getId())) {
                    result.add(c);
                }
            }
        }
        return result;
    }

    private List<Network> networks(String runId) {
        Set<String> seen = new LinkedHashSet<>();
        List<Network> result = new ArrayList<>();
        for (String label : List.of(Labels.RUN, Labels.PARENT_RUN)) {
            for (Network n : docker.listNetworksCmd()
                    .withFilter("label", List.of(Labels.MANAGED + "=true", label + "=" + runId)).exec()) {
                if (seen.add(n.getId())) {
                    result.add(n);
                }
            }
        }
        return result;
    }

    private static String name(Container c) {
        return c.getNames() == null || c.getNames().length == 0 ? c.getId() : c.getNames()[0].replaceFirst("^/", "");
    }
}
