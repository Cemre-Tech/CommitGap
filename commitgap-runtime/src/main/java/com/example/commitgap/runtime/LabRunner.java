package com.example.commitgap.runtime;

import com.example.commitgap.core.CommitGapVersion;
import com.example.commitgap.core.invariant.InvariantEvaluator;
import com.example.commitgap.core.invariant.InvariantResult;
import com.example.commitgap.core.model.FaultAction;
import com.example.commitgap.core.model.Outcome;
import com.example.commitgap.core.model.ProcessRole;
import com.example.commitgap.core.model.Strategy;
import com.example.commitgap.core.result.EnvironmentInfo;
import com.example.commitgap.core.result.Expectation;
import com.example.commitgap.core.result.FaultExecution;
import com.example.commitgap.core.result.Measurements;
import com.example.commitgap.core.result.OutcomeClassifier;
import com.example.commitgap.core.result.RunResult;
import com.example.commitgap.core.result.TimelineEvent;
import com.example.commitgap.core.scenario.Fault;
import com.example.commitgap.core.scenario.Scenario;
import com.example.commitgap.core.snapshot.BrokerSnapshot;
import com.example.commitgap.core.snapshot.DeliveryRecord;
import com.example.commitgap.core.snapshot.LabSnapshot;
import com.example.commitgap.core.snapshot.PublishAttempt;
import com.example.commitgap.core.snapshot.StockMovement;
import com.example.commitgap.core.workload.WorkloadPlan;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs one scenario against one strategy in a fresh lab and returns the measured result. This is the
 * runtime's entry point for the CLI.
 *
 * <p>Order of work: start the lab, prepare the fault (arm checkpoint, duplicate switch or relay
 * barrier), drive the workload, wait for the fault and recovery, observe until settled or timed out,
 * read both databases, evaluate the invariants, classify, then keep or remove the resources.
 */
public final class LabRunner {

    /** Labs still running, with whether they should be kept if the process is interrupted. */
    private static final Map<Lab, Boolean> ACTIVE = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Interrupted (Ctrl+C) or crashed: an interrupted run is a failed run.
            ACTIVE.forEach((lab, keep) -> {
                try {
                    if (keep) {
                        System.err.println("Interrupted; kept for inspection: " + String.join(", ", lab.resources())
                                + " (remove with: commitgap cleanup --run " + lab.runId() + ")");
                    } else {
                        lab.close();
                    }
                } catch (RuntimeException ignored) {
                    // best effort during shutdown
                }
            });
        }, "commitgap-cleanup"));
    }

    private final RuntimeSettings settings;
    private final RunStore store;

    public LabRunner(RuntimeSettings settings) {
        this.settings = settings;
        this.store = new RunStore(settings.runsDirectory());
    }

    public RunStore store() {
        return store;
    }

    public RunResult run(Scenario scenario, Strategy strategy, String runId, String parentRunId) {
        Instant startedAt = Instant.now();
        Path dir = store.create(runId, parentRunId);
        RunManifest manifest = new RunManifest(RunManifest.VERSION, runId, parentRunId, "run",
                List.of(scenario.id()), List.of(strategy.id()), startedAt, null, "running", false, List.of(),
                List.of(), CommitGapVersion.version());
        store.writeManifest(dir, manifest);
        Timeline timeline = new Timeline(dir.resolve(RunStore.TIMELINE));
        try {
            return execute(scenario, strategy, runId, parentRunId, dir, manifest, timeline, startedAt);
        } finally {
            timeline.close();
        }
    }

    private RunResult execute(Scenario scenario, Strategy strategy, String runId, String parentRunId, Path dir,
                              RunManifest manifest, Timeline timeline, Instant startedAt) {
        WorkloadPlan plan = WorkloadPlan.from(scenario.workload());
        Outcome expected = scenario.expectedFor(strategy);
        String namespace = Labels.namespace(runId);

        if (!scenario.isApplicableTo(strategy)) {
            Fault fault = scenario.fault().orElseThrow();
            timeline.runner("not-applicable", "fault target '" + fault.target().id() + "' does not exist in " + strategy.id());
            FaultExecution fe = FaultExecution.notApplicable(fault.describe(), fault.target().id());
            var c = OutcomeClassifier.classify(false, List.of(), fe, List.of());
            store.writeManifest(dir, manifest.withStatus("not-applicable", Instant.now(), false, List.of()));
            return new RunResult(runId, parentRunId, RunResult.ScenarioSummary.of(scenario), strategy.id(), startedAt,
                    Instant.now(), new RunResult.ObservationSummary(scenario.observation().timeoutSeconds(), 0, "skipped"),
                    c.outcome(), c.reasons(), expected, Expectation.compare(c.outcome(), expected), List.of(), null, fe,
                    timeline.merged(List.of()), EnvironmentProbe.describe(namespace, settings.demoJar(), List.of()),
                    List.of());
        }

        List<String> obstacles = Collections.synchronizedList(new ArrayList<>());
        Fault fault = scenario.fault().orElse(null);
        boolean killProducerWithRelay = fault != null && fault.action() == FaultAction.KILL
                && fault.target().processIn(strategy).orElse(null) == ProcessRole.PRODUCER && strategy.usesOutboxRelay();

        progress(runId, "starting lab " + namespace);
        Lab lab = new Lab(runId, parentRunId, strategy, scenario.workload(), settings.demoJar(),
                settings.startupTimeout(), !killProducerWithRelay);
        ACTIVE.put(lab, settings.keep() != RuntimeSettings.KeepPolicy.NEVER);
        timeline.redactWith(lab::redact);
        FaultExecution faultExecution = fault == null ? FaultExecution.notRequired() : null;
        RunResult.ObservationSummary observation = new RunResult.ObservationSummary(
                scenario.observation().timeoutSeconds(), 0, "skipped");
        LabSnapshot snapshot = null;
        FaultSupervisor supervisor = null;
        boolean labStarted = false;
        try {
            lab.start();
            labStarted = true;
            timeline.runner("lab-ready", "containers healthy: " + String.join(", ", lab.resources()));
            store.writeManifest(dir, manifest.withStatus("running", null, false, lab.resources()));

            if (fault != null && fault.action() == FaultAction.KILL) {
                ProcessRole role = fault.target().processIn(strategy).orElseThrow();
                DemoProcess relay = killProducerWithRelay ? lab.require(ProcessRole.RELAY) : null;
                if (relay != null) {
                    timeline.runner("relay-barrier", "relay started with its gate closed until the producer checkpoint is handled");
                }
                supervisor = new FaultSupervisor(fault, lab.require(role), scenario.recovery().action(), relay,
                        timeline, settings.startupTimeout());
                supervisor.armAndStart();
            } else if (fault != null && fault.action() == FaultAction.DUPLICATE_PUBLISH) {
                ProcessRole role = fault.target().processIn(strategy).orElseThrow();
                lab.require(role).control().armDuplicatePublish(fault.occurrence(), fault.copies());
                timeline.runner("fault-armed", "duplicate publish armed on " + role.id() + ": " + fault.describe());
            }

            progress(runId, "sending " + plan.orders().size() + " orders");
            WorkloadDriver driver = new WorkloadDriver(lab, plan, strategy, fault, supervisor, timeline, obstacles,
                    settings.startupTimeout());
            driver.run();
            if (supervisor != null && !supervisor.isDone()) {
                supervisor.openRelayGate("workload finished before the producer checkpoint was handled");
            }

            progress(runId, "observing (up to " + scenario.observation().timeoutSeconds() + "s)");
            Observer.Result observed = new Observer(lab.snapshotReader(), lab.brokerProbe(), strategy.usesOutboxRelay(),
                    supervisor).observe(scenario.observation());
            observation = new RunResult.ObservationSummary(scenario.observation().timeoutSeconds(),
                    observed.observedMillis(), observed.endReason());
            timeline.runner("observation-ended", observed.endReason() + " after " + observed.observedMillis() + " ms"
                    + (observed.lastProblem() == null ? "" : " (last problem: " + observed.lastProblem() + ")"));

            if (supervisor != null) {
                supervisor.stop();
                faultExecution = supervisor.result();
            } else if (fault != null && fault.action() == FaultAction.NETWORK_CUT) {
                faultExecution = driver.networkExecution() == null
                        ? new FaultExecution(FaultExecution.Status.NOT_VERIFIED, fault.describe(), fault.target().id(),
                        null, null, Map.of(), null, null, null, null, "workload ended before the cut")
                        : driver.networkExecution();
            }

            progress(runId, "reading database snapshots");
            SnapshotReader reader = lab.snapshotReader();
            BrokerSnapshot broker = lab.brokerProbe().snapshot();
            snapshot = new LabSnapshot(reader.producer(), reader.consumer(), broker, Instant.now());
            if (fault != null && fault.action() == FaultAction.DUPLICATE_PUBLISH) {
                faultExecution = verifyDuplicate(fault, snapshot);
            }
        } catch (Exception e) {
            obstacles.add(lab.redact((labStarted ? "lab error: " : "environment could not be prepared: ") + Lab.rootMessage(e)));
            timeline.runner("lab-error", Lab.rootMessage(e));
        } finally {
            if (supervisor != null && !supervisor.isDone()) {
                supervisor.stop();
            }
        }
        if (faultExecution == null) {
            faultExecution = supervisor != null ? supervisor.result()
                    : new FaultExecution(FaultExecution.Status.NOT_VERIFIED, fault.describe(), fault.target().id(),
                    null, null, Map.of(), null, null, null, null, "run ended before the fault was verified");
        }

        faultExecution = redacted(faultExecution, lab);
        obstacles.replaceAll(lab::redact);

        List<InvariantResult> invariants = List.of();
        Measurements measurements = null;
        if (snapshot != null) {
            invariants = new InvariantEvaluator(plan, strategy, snapshot).evaluate(scenario.assertions());
            measurements = Measurements.of(plan, strategy, snapshot);
        }
        var classification = OutcomeClassifier.classify(true, List.copyOf(obstacles), faultExecution, invariants);
        Expectation expectation = Expectation.compare(classification.outcome(), expected);

        lab.collectLogs(dir.resolve("logs"));
        List<String> resources = lab.resources();
        EnvironmentInfo environment = EnvironmentProbe.describe(namespace, settings.demoJar(), resources);
        boolean failed = classification.outcome() == Outcome.INCONCLUSIVE || expectation == Expectation.NOT_MATCHED;
        boolean keep = settings.keep() == RuntimeSettings.KeepPolicy.ALWAYS
                || (settings.keep() == RuntimeSettings.KeepPolicy.ON_FAILURE && failed);
        List<String> kept = List.of();
        if (keep) {
            kept = new ArrayList<>(new ResourceJanitor().list(runId));
            kept.addAll(lab.accessHints());
            timeline.runner("resources-kept", "kept for inspection: " + String.join("; ", kept));
        } else {
            ACTIVE.remove(lab);
            lab.close();
            timeline.runner("resources-removed", "removed the run's containers and network");
        }
        ACTIVE.remove(lab);
        store.writeManifest(dir, manifest.withStatus(obstacles.isEmpty() ? "completed" : "error", Instant.now(), keep,
                keep ? kept : resources));

        List<TimelineEvent> processEvents = snapshot == null ? List.of() : processEvents(snapshot);
        return new RunResult(runId, parentRunId, RunResult.ScenarioSummary.of(scenario), strategy.id(), startedAt,
                Instant.now(), observation, classification.outcome(), classification.reasons(), expected, expectation,
                invariants, measurements, faultExecution, timeline.merged(processEvents), environment,
                List.copyOf(obstacles));
    }

    private static FaultExecution redacted(FaultExecution f, Lab lab) {
        return new FaultExecution(f.status(), f.description(), f.target(), f.checkpoint(), f.checkpointReachedAt(),
                f.checkpointContext(), f.appliedAt(), lab.redact(f.verification()), f.recovery(), f.recoveredAt(),
                lab.redact(f.problem()));
    }

    private static FaultExecution verifyDuplicate(Fault fault, LabSnapshot snapshot) {
        Map<UUID, Long> confirmed = snapshot.producer().publishAttempts().stream()
                .filter(a -> "CONFIRMED".equals(a.result()))
                .collect(Collectors.groupingBy(PublishAttempt::eventId, Collectors.counting()));
        return confirmed.entrySet().stream().filter(e -> e.getValue() >= fault.copies()).findFirst()
                .map(e -> new FaultExecution(FaultExecution.Status.APPLIED, fault.describe(), fault.target().id(), null,
                        null, Map.of("eventId", e.getKey().toString()), null,
                        "event " + e.getKey() + " was confirmed by the broker " + e.getValue() + " times", null, null, null))
                .orElseGet(() -> new FaultExecution(FaultExecution.Status.NOT_VERIFIED, fault.describe(),
                        fault.target().id(), null, null, Map.of(), null, null, null, null,
                        "no event was confirmed " + fault.copies() + " times"));
    }

    /** Explanatory timeline entries derived from the logged rows. They never decide the outcome. */
    private static List<TimelineEvent> processEvents(LabSnapshot s) {
        List<TimelineEvent> events = new ArrayList<>();
        Function<UUID, String> shortId = id -> id == null ? "?" : id.toString().substring(0, 8);
        for (PublishAttempt a : s.producer().publishAttempts()) {
            events.add(new TimelineEvent(a.at(), a.publisher(), "publish",
                    "publish " + shortId.apply(a.eventId()) + ": " + a.result() + (a.detail() == null ? "" : " (" + a.detail() + ")"),
                    Map.of("eventId", a.eventId().toString())));
        }
        for (DeliveryRecord d : s.consumer().deliveries()) {
            events.add(new TimelineEvent(d.receivedAt(), "consumer", "delivery",
                    "delivery " + shortId.apply(d.eventId()) + (d.redelivered() ? " (redelivered)" : "") + " -> "
                            + (d.outcome() == null ? "no outcome recorded (process stopped)" : d.outcome()),
                    Map.of("eventId", String.valueOf(d.eventId()), "worker", d.worker())));
        }
        for (StockMovement m : s.consumer().movements()) {
            events.add(new TimelineEvent(m.appliedAt(), "consumer", "business-effect",
                    "stock " + m.delta() + " for event " + shortId.apply(m.eventId()),
                    Map.of("eventId", m.eventId().toString(), "orderId", m.orderId().toString())));
        }
        return events;
    }

    private void progress(String runId, String message) {
        settings.progress().step(runId, message);
    }
}
