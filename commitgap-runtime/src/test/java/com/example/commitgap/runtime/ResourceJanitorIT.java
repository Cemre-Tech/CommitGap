package com.example.commitgap.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * Creates labelled resources for two runs plus one unlabelled container, then cleans up one run.
 * Uses a small pinned image already needed by the lab.
 */
class ResourceJanitorIT {

    private final DockerClient docker = DockerClientFactory.instance().client();
    private final List<String> created = new ArrayList<>();
    private final List<String> networks = new ArrayList<>();

    @AfterEach
    void removeLeftovers() {
        for (String id : created) {
            try {
                docker.removeContainerCmd(id).withForce(true).exec();
            } catch (RuntimeException ignored) {
                // already removed by the test
            }
        }
        for (String id : networks) {
            try {
                docker.removeNetworkCmd(id).exec();
            } catch (RuntimeException ignored) {
                // already removed by the test
            }
        }
    }

    @Test
    void cleanupOfOneRunLeavesOtherRunsAndForeignContainersAlone() {
        String runA = RunIds.next();
        String runB = RunIds.next();
        String parent = RunIds.next();
        String child = RunIds.child(parent, "happy-path-outbox-idempotent");
        resourcesFor(runA, null);
        resourcesFor(runB, null);
        resourcesFor(child, parent);
        String foreign = container("commitgap-foreign-" + runA, Map.of("purpose", "not created by commitgap", Labels.RUN, runA));

        ResourceJanitor janitor = new ResourceJanitor();
        ResourceJanitor.Removal removal = janitor.removeRun(runA);

        assertThat(removal.failed()).isEmpty();
        assertThat(removal.removed()).hasSize(2);
        assertThat(janitor.list(runA)).isEmpty();
        assertThat(janitor.list(runB)).as("another run's resources").hasSize(2);
        assertThat(janitor.list(parent)).as("cells of a compare run").hasSize(2);
        assertThat(exists(foreign)).as("a container without commitgap.managed=true, even with a matching run label").isTrue();

        assertThat(janitor.removeRun(parent).removed()).hasSize(2);
        assertThat(janitor.list(child)).isEmpty();
        assertThat(janitor.list(runB)).hasSize(2);
    }

    private void resourcesFor(String runId, String parent) {
        container(Labels.namespace(runId) + "-probe", Labels.forRun(runId, parent, "probe"));
        networks.add(docker.createNetworkCmd().withName(Labels.namespace(runId))
                .withLabels(Labels.forRun(runId, parent, "network")).exec().getId());
    }

    private String container(String name, Map<String, String> labels) {
        String id = docker.createContainerCmd(LabImages.TOXIPROXY).withName(name).withLabels(labels).exec().getId();
        created.add(id);
        return id;
    }

    private boolean exists(String id) {
        List<Container> all = docker.listContainersCmd().withShowAll(true).withIdFilter(List.of(id)).exec();
        return !all.isEmpty();
    }
}
