package com.example.commitgap.runtime;

import com.example.commitgap.core.model.ProcessRole;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * One demo process (producer, relay or consumer) running in its own container. Killing it uses
 * SIGKILL through the Docker API and is verified by inspecting the container state; it is never an
 * exception or a graceful shutdown. Restart starts the same container again, so the process comes
 * back with the same configuration and no in-memory state.
 */
public final class DemoProcess {

    public static final int CONTROL_PORT = 8080;
    /** Exit status of a process terminated by SIGKILL (128 + 9). */
    public static final long SIGKILL_EXIT_CODE = 137;

    private final ProcessRole role;
    private final GenericContainer<?> container;
    private final String containerName;
    private final ControlClient control;
    private volatile int hostPort;

    public record KillResult(boolean stopped, Long exitCode, String detail) {
        public boolean verified() {
            return stopped && exitCode != null && exitCode == SIGKILL_EXIT_CODE;
        }
    }

    DemoProcess(ProcessRole role, GenericContainer<?> container, String containerName) {
        this.role = role;
        this.container = container;
        this.containerName = containerName;
        this.control = new ControlClient(this);
    }

    public ProcessRole role() {
        return role;
    }

    public String containerName() {
        return containerName;
    }

    public ControlClient control() {
        return control;
    }

    GenericContainer<?> container() {
        return container;
    }

    /** Called after the container (re)started: the host port mapping can change on every start. */
    void refreshPort() {
        InspectContainerResponse info = docker().inspectContainerCmd(container.getContainerId()).exec();
        Ports.Binding[] bindings = info.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(CONTROL_PORT));
        if (bindings == null || bindings.length == 0) {
            throw new LabException(containerName + " has no host port for " + CONTROL_PORT);
        }
        hostPort = Integer.parseInt(bindings[0].getHostPortSpec());
    }

    URI baseUri() {
        return URI.create("http://" + DockerClientFactory.instance().dockerHostIpAddress() + ":" + hostPort);
    }

    /** Sends SIGKILL and waits until Docker reports the container as stopped. */
    public KillResult kill(Duration deadline) {
        String id = container.getContainerId();
        try {
            docker().killContainerCmd(id).withSignal("KILL").exec();
        } catch (RuntimeException e) {
            return new KillResult(false, null, "docker kill failed: " + e.getMessage());
        }
        Instant until = Instant.now().plus(deadline);
        while (Instant.now().isBefore(until)) {
            InspectContainerResponse.ContainerState state = docker().inspectContainerCmd(id).exec().getState();
            if (!Boolean.TRUE.equals(state.getRunning())) {
                Long exit = state.getExitCodeLong();
                return new KillResult(true, exit, "container " + containerName + " stopped, exit code " + exit
                        + (Boolean.TRUE.equals(state.getOOMKilled()) ? " (OOM killed)" : ""));
            }
            sleep(50);
        }
        return new KillResult(false, null, "container " + containerName + " still running " + deadline.toSeconds()
                + "s after SIGKILL");
    }

    /** Starts the stopped container again and waits until its control endpoint answers. */
    public void restart(Duration startupTimeout) {
        docker().startContainerCmd(container.getContainerId()).exec();
        refreshPort();
        awaitHealthy(startupTimeout);
    }

    public void awaitHealthy(Duration timeout) {
        Instant until = Instant.now().plus(timeout);
        String last = "no answer";
        while (Instant.now().isBefore(until)) {
            try {
                if (control.healthy()) {
                    return;
                }
            } catch (RuntimeException e) {
                last = e.getMessage();
            }
            if (!isRunning()) {
                throw new LabException(containerName + " exited while starting (exit code " + exitCode() + ")");
            }
            sleep(200);
        }
        throw new LabException(containerName + " did not become healthy within " + timeout.toSeconds() + "s: " + last);
    }

    public boolean isRunning() {
        return Boolean.TRUE.equals(docker().inspectContainerCmd(container.getContainerId()).exec().getState().getRunning());
    }

    private Long exitCode() {
        return docker().inspectContainerCmd(container.getContainerId()).exec().getState().getExitCodeLong();
    }

    private static DockerClient docker() {
        return DockerClientFactory.instance().client();
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LabException("interrupted");
        }
    }
}
