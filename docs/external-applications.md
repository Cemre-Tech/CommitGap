# External application integration (Java SDK)

`commitgap-adapter` is a small Java 21 SDK for application-owned checkpoint hooks
and observation. The first working integration is the standalone
[Spring JDBC example](../examples/external-spring/README.md), outside the built-in
Docker demo. The adapter depends only on `commitgap-core`; it does not add Spring,
a database driver, shell commands, or Docker to your application.

This release supports the existing **order / single-SKU stock ledger** invariant
model. An application must map its committed requests, event IDs, effects, stock
balance, outbox, dedup records, and broker counters to these core types. It is not
a generic invariant language or arbitrary-application Docker orchestrator. The
caller owns starting the application, submitting the deterministic workload,
fault selection, recovery, and stopping writers for observation. The existing CLI
continues to run its built-in Docker laboratory; external verification is an SDK
entry point.

## Read-only observation

Implement `ExternalApplicationAdapter.capture(WorkloadPlan)` with a read-only,
run-scoped query path returning:

```java
new ExternalObservation(
    new LabSnapshot(producerSnapshot, consumerSnapshot, brokerSnapshot, capturedAt),
    workloadCompleted,
    quiescent,
    List.of()); // safe descriptions of any measurement obstacles
```

Read business balance and ledger within the same repeatable-read transaction in
each database. Stop all writers and prevent new ones while capturing across
producer/consumer databases and broker; a transaction in one database is not a
distributed snapshot. `quiescent` means writers are stopped, not that pending work
has disappeared. Include any remaining outbox rows and transport backlog so a
timeout is not misreported as permanent loss. Scope every query to the run: event
IDs repeat with the workload seed, so those IDs alone may not isolate two runs.

Use the exact `WorkloadPlan` identifiers when submitting requests and retries.
Map `OrderRow` to actual committed producer rows, `StockMovement` to the durable
consumer business ledger, and `ProcessedMessage` to committed dedup rows. Do not
infer business effects from application logs. Preserve unknown broker state with
`BrokerSnapshot.unavailable(...)`; never turn a failed broker read into zero
backlog. Throw on failed business reads rather than returning empty lists.

```java
var result = ExternalApplicationVerifier.verify(plan, strategy, applicationAdapter);
System.out.println(result.classification().outcome());
// result.invariants(): per-event evidence; result.measurements(): observed counters
```

All six existing invariants run. Read errors, incomplete workload driving,
non-quiescent capture, and absent non-rollback planned requests return
`INCONCLUSIVE`. A read error does not expose its exception message, because JDBC
errors can include connection credentials. Any obstacle strings your adapter
supplies must also be credential-free. Observed invariant failures return
`VIOLATION_OBSERVED` when capture was valid; `CONSISTENT` describes only this run
and its observation window. Expected outcomes never determine actual outcomes.

## Cooperative checkpoint coordination

Instrument after a transaction has returned from commit, or after the actual
transport stage named by the checkpoint. Placing the hook inside a transaction
when the checkpoint says "after commit" invalidates the experiment.

```java
transactionTemplate.executeWithoutResult(status -> persistOrderAndOutbox(order));
signal.hit(Checkpoint.PRODUCER_AFTER_DB_COMMIT_BEFORE_PUBLISH, order.eventId());
```

Use `CheckpointSignal.disabled()` when no fault is armed. A `CheckpointGate`
targets one checkpoint and one event ID; other events continue. On a worker
thread, the matching `hit` pauses for at most the configured duration. The
controller waits with `awaitReached(timeout)`, then calls `release()` or
`abort()`. Closing the gate releases/disarms it. The first decision wins and a
gate pauses only once, including during recovery. Interruptions and timeouts
propagate to the application; they are measurement obstacles, not successful
faults. Join the worker and confirm the requested fault occurred before declaring
the observation valid, as the example does.

`abort()` throws `CheckpointGate.FaultInjectedException` in the worker. This is a
cooperative fault: finally blocks and managed-resource cleanup can still run. It
does not kill the JVM and cannot establish process-crash safety. Actual SIGKILL,
broker confirmation ambiguity, network partitions, and multi-process
orchestration remain in the Docker laboratory or caller-owned test infrastructure.
The SDK sends no messages, executes no external commands, and changes no data.

The Spring example deliberately uses a local queue model while testing real H2
database commits. Its post-commit consumer fault leaves an envelope for explicit
redelivery: a non-idempotent consumer adds a second ledger row; the idempotent
consumer's transaction leaves one row and one dedup record. These experiments
exercise the adapter contract without claiming to simulate RabbitMQ internals.
