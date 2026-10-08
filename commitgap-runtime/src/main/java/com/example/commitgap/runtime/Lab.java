package com.example.commitgap.runtime;

import com.example.commitgap.core.model.ProcessRole;
import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.scenario.Workload;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The environment of one run: its own Docker network, one PostgreSQL server with separate producer
 * and consumer databases, RabbitMQ, Toxiproxy on the publisher-to-broker path, and one container per
 * demo process. All names start with the run namespace and every resource carries the run labels.
 * Ports are random host ports, credentials are generated per run, so concurrent runs cannot collide.
 */
public final class Lab implements AutoCloseable {

    static final String BROKER_PROXY = "rabbitmq-publishers";
    private static final int PROXY_PORT = 8666;

    private final String runId;
    private final String parentRunId;
    private final Strategy strategy;
    private final Workload workload;
    private final Path demoJar;
    private final Duration startupTimeout;
    private final boolean relayGateInitiallyOpen;
    private final String namespace;
    private final String dbPassword = RunIds.secret();
    private final String brokerPassword = RunIds.secret();

    private Network network;
    private PostgreSQLContainer postgres;
    private RabbitMQContainer rabbit;
    private ToxiproxyContainer toxiproxy;
    private Proxy brokerProxy;
    private final Map<ProcessRole, DemoProcess> processes = new EnumMap<>(ProcessRole.class);
    private final List<GenericContainer<?>> started = new ArrayList<>();

    public Lab(String runId, String parentRunId, Strategy strategy, Workload workload, Path demoJar,
               Duration startupTimeout, boolean relayGateInitiallyOpen) {
        this.runId = runId;
        this.parentRunId = parentRunId;
        this.strategy = strategy;
        this.workload = workload;
        this.demoJar = demoJar;
        this.startupTimeout = startupTimeout;
        this.relayGateInitiallyOpen = relayGateInitiallyOpen;
        this.namespace = Labels.namespace(runId);
    }

    public String namespace() {
        return namespace;
    }

    public String runId() {
        return runId;
    }

    public void start() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            throw new LabException("Docker is not available. Start Docker and run 'commitgap doctor'.");
        }
        network = Network.builder()
                .createNetworkCmdModifier(cmd -> cmd.withName(namespace)
                        .withLabels(Labels.forRun(runId, parentRunId, "network")))
                .build();
        network.getId();

        postgres = new PostgreSQLContainer(DockerImageName.parse(LabImages.POSTGRES))
                .withDatabaseName("producer")
                .withUsername("commitgap")
                .withPassword(dbPassword)
                .withCopyToContainer(Transferable.of("CREATE DATABASE consumer;\n"),
                        "/docker-entrypoint-initdb.d/10-consumer-database.sql");
        infrastructure(postgres, "postgres");

        rabbit = new RabbitMQContainer(DockerImageName.parse(LabImages.RABBITMQ))
                .withAdminUser("commitgap")
                .withAdminPassword(brokerPassword)
                .withCopyToContainer(Transferable.of("collect_statistics_interval = 500\n"),
                        "/etc/rabbitmq/conf.d/90-commitgap.conf");
        infrastructure(rabbit, "rabbitmq");

        toxiproxy = new ToxiproxyContainer(DockerImageName.parse(LabImages.TOXIPROXY));
        infrastructure(toxiproxy, "toxiproxy");

        try {
            Startables.deepStart(postgres, rabbit, toxiproxy).get(startupTimeout.toSeconds() * 2, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new LabException("infrastructure containers did not start: " + rootMessage(e), e);
        }
        started.addAll(List.of(postgres, rabbit, toxiproxy));

        try {
            ToxiproxyClient client = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
            brokerProxy = client.createProxy(BROKER_PROXY, "0.0.0.0:" + PROXY_PORT, "rabbitmq:5672");
        } catch (IOException e) {
            throw new LabException("cannot create the Toxiproxy broker link: " + e.getMessage(), e);
        }

        List<GenericContainer<?>> apps = new ArrayList<>();
        for (ProcessRole role : ProcessRole.values()) {
            if (role.runsIn(strategy)) {
                DemoProcess process = demoProcess(role);
                processes.put(role, process);
                apps.add(process.container());
            }
        }
        try {
            Startables.deepStart(apps).get(startupTimeout.toSeconds() + 30, TimeUnit.SECONDS);
        } catch (Exception e) {
            started.addAll(apps);
            // Testcontainers' own message embeds the container environment (credentials), so describe states instead.
            throw new LabException("demo processes did not start: " + describeStates(apps));
        }
        started.addAll(apps);
        processes.values().forEach(DemoProcess::refreshPort);
    }

    private String describeStates(List<GenericContainer<?>> containers) {
        List<String> states = new ArrayList<>();
        for (GenericContainer<?> c : containers) {
            String role = c.getLabels().getOrDefault(Labels.COMPONENT, "container");
            String id = c.getContainerId();
            if (id == null) {
                states.add(role + " was not created");
                continue;
            }
            try {
                var state = DockerClientFactory.instance().client().inspectContainerCmd(id).exec().getState();
                states.add(Boolean.TRUE.equals(state.getRunning())
                        ? role + " running but not healthy within " + startupTimeout.toSeconds() + "s"
                        : role + " exited with code " + state.getExitCodeLong());
            } catch (RuntimeException e) {
                states.add(role + " state unknown");
            }
        }
        return String.join("; ", states) + " (container logs are in the run directory)";
    }

    /** Removes this run's credentials (and anything that looks like one) from text bound for reports. */
    public String redact(String text) {
        return Redaction.redact(text, List.of(dbPassword, brokerPassword));
    }

    private void infrastructure(GenericContainer<?> container, String alias) {
        container.withNetwork(network)
                .withNetworkAliases(alias)
                .withLabels(Labels.forRun(runId, parentRunId, alias))
                .withCreateContainerCmdModifier(cmd -> cmd.withName(namespace + "-" + alias));
    }

    private DemoProcess demoProcess(ProcessRole role) {
        String name = namespace + "-" + role.id();
        boolean publisher = role != ProcessRole.CONSUMER;
        Map<String, String> env = new LinkedHashMap<>();
        env.put("COMMITGAP_ROLE", role.id());
        env.put("COMMITGAP_STRATEGY", strategy.id());
        env.put("COMMITGAP_CONSUMER_WORKERS", String.valueOf(workload.consumerWorkers()));
        env.put("COMMITGAP_INITIAL_STOCK", String.valueOf(workload.initialStock()));
        env.put("COMMITGAP_RELAY_GATE_OPEN", String.valueOf(relayGateInitiallyOpen));
        env.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/" + (publisher ? "producer" : "consumer"));
        env.put("SPRING_DATASOURCE_USERNAME", "commitgap");
        env.put("SPRING_DATASOURCE_PASSWORD", dbPassword);
        // Publishers reach the broker through Toxiproxy so the link can be cut; the consumer connects directly.
        env.put("SPRING_RABBITMQ_HOST", publisher ? "toxiproxy" : "rabbitmq");
        env.put("SPRING_RABBITMQ_PORT", String.valueOf(publisher ? PROXY_PORT : 5672));
        env.put("SPRING_RABBITMQ_USERNAME", "commitgap");
        env.put("SPRING_RABBITMQ_PASSWORD", brokerPassword);

        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(LabImages.JAVA_RUNTIME))
                .withCopyFileToContainer(MountableFile.forHostPath(demoJar), "/app/commitgap-demo.jar")
                .withCommand("java", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xmx192m",
                        "-jar", "/app/commitgap-demo.jar")
                .withEnv(env)
                .withExposedPorts(DemoProcess.CONTROL_PORT)
                .waitingFor(Wait.forHttp("/control/health").forPort(DemoProcess.CONTROL_PORT).forStatusCode(200)
                        .withStartupTimeout(startupTimeout));
        container.withNetwork(network)
                .withNetworkAliases(role.id())
                .withLabels(Labels.forRun(runId, parentRunId, role.id()))
                .withCreateContainerCmdModifier(cmd -> cmd.withName(name));
        return new DemoProcess(role, container, name);
    }

    public Optional<DemoProcess> process(ProcessRole role) {
        return Optional.ofNullable(processes.get(role));
    }

    public DemoProcess require(ProcessRole role) {
        return process(role).orElseThrow(() -> new LabException("no " + role.id() + " in strategy " + strategy.id()));
    }

    public void cutBrokerLink() throws IOException {
        brokerProxy.disable();
    }

    public void restoreBrokerLink() throws IOException {
        brokerProxy.enable();
    }

    /** Asks Toxiproxy for the current state instead of trusting the cached proxy object. */
    public boolean brokerLinkEnabled() throws IOException {
        return new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort()).getProxy(BROKER_PROXY).isEnabled();
    }

    SnapshotReader snapshotReader() {
        String base = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/";
        return new SnapshotReader(new SnapshotReader.Database(base + "producer", "commitgap", dbPassword),
                new SnapshotReader.Database(base + "consumer", "commitgap", dbPassword));
    }

    BrokerProbe brokerProbe() {
        return new BrokerProbe(command -> {
            try {
                org.testcontainers.containers.Container.ExecResult r = rabbit.execInContainer(command);
                if (r.getExitCode() != 0) {
                    throw new IOException(String.join(" ", command) + " exited with " + r.getExitCode() + ": "
                            + r.getStderr().strip());
                }
                return r.getStdout();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }, rabbit.getHttpUrl(), "commitgap", brokerPassword);
    }

    /** Container and network names of this run, for manifests and kept-resource listings. */
    public List<String> resources() {
        List<String> names = new ArrayList<>();
        names.add("network " + namespace);
        for (String component : List.of("postgres", "rabbitmq", "toxiproxy")) {
            names.add("container " + namespace + "-" + component);
        }
        processes.values().forEach(p -> names.add("container " + p.containerName()));
        return names;
    }

    /** Connection details for kept resources. Never includes credentials. */
    public List<String> accessHints() {
        List<String> hints = new ArrayList<>();
        if (postgres != null && postgres.isRunning()) {
            hints.add("postgres: " + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                    + " (databases producer, consumer; user commitgap; password generated for this run, see docker inspect)");
        }
        if (rabbit != null && rabbit.isRunning()) {
            hints.add("rabbitmq management: " + rabbit.getHttpUrl());
        }
        return hints;
    }

    /** Copies each container's stdout/stderr (all starts, including before a kill) into the run directory. */
    public void collectLogs(Path logDirectory) {
        for (GenericContainer<?> c : started) {
            String id = c.getContainerId();
            if (id == null) {
                continue;
            }
            try {
                String name = DockerClientFactory.instance().client().inspectContainerCmd(id).exec().getName()
                        .replaceFirst("^/", "");
                Path file = logDirectory.resolve(name + ".log");
                Files.createDirectories(logDirectory);
                try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    DockerClientFactory.instance().client().logContainerCmd(id)
                            .withStdOut(true).withStdErr(true).withTimestamps(true)
                            .exec(new ResultCallback.Adapter<Frame>() {
                                @Override
                                public void onNext(Frame frame) {
                                    try {
                                        out.write(new String(frame.getPayload(), StandardCharsets.UTF_8));
                                    } catch (IOException ignored) {
                                        // partial log is better than none
                                    }
                                }
                            }).awaitCompletion(15, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                // Logs explain results; failing to copy them must not change the result.
            }
        }
    }

    @Override
    public void close() {
        for (int i = started.size() - 1; i >= 0; i--) {
            try {
                started.get(i).stop();
            } catch (RuntimeException ignored) {
                // the janitor removes anything left behind by label
            }
        }
        if (network != null) {
            try {
                network.close();
            } catch (RuntimeException ignored) {
                // removed by label below
            }
        }
        new ResourceJanitor().removeRun(runId);
    }

    static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
