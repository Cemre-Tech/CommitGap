# Architecture

CommitGap answers one question with measurements: *if a backend operation is cut off between writing
to its database and delivering its message, or while a message is being reprocessed, what happens to
the business records?*

It does that by running a small order/stock system in containers, stopping it at precisely defined
points, and comparing the producer and consumer databases afterwards.

## Modules

| Module | Responsibility | Depends on |
|---|---|---|
| `commitgap-core` | Scenario model and strict YAML loader, deterministic workload plan, snapshot model, invariants, outcome classification, result types. No Spring, no Docker. | SnakeYAML |
| `commitgap-runtime` | Lab environment per run, checkpoint coordination, fault application (SIGKILL, Toxiproxy), observation, database and broker snapshots, label-based cleanup, run directories. | core, Testcontainers, PostgreSQL JDBC, Jackson |
| `commitgap-demo` | The system under test: one Spring Boot jar that runs as producer, relay or consumer for each strategy. | core, Spring Boot (JDBC, AMQP, Web MVC, Flyway) |
| `commitgap-report` | Versioned JSON report and self-contained HTML. | core, Jackson |
| `commitgap-cli` | `commitgap` commands, options, console output, exit codes; integration tests that need the demo jar. | core, runtime, report, picocli |

`scenarios/` holds the bundled scenario files; `docs/` holds this documentation and the ADRs.

## One run

```
commitgap run --scenario crash-after-commit --strategy outbox-idempotent
```

1. **Load and validate** the scenario (`ScenarioLoader`). Problems are reported all at once; nothing
   in the file is executed.
2. **Plan the workload** from the seed (`WorkloadPlan`): order ids, event ids, which orders roll back.
3. **Start a lab** (`Lab`) under the namespace `commitgap-<run-id>`:
   - a Docker network `commitgap-<run-id>`
   - PostgreSQL with two databases, `producer` and `consumer`
   - RabbitMQ (quorum queue with a delivery limit and a dead-letter queue)
   - Toxiproxy between the publishing processes and RabbitMQ
   - one container per demo process: `producer`, `relay` (outbox strategies only), `consumer`

   Every container and the network carry the labels `commitgap.managed=true` and
   `commitgap.run=<run-id>`; credentials are generated for the run; host ports are random.
4. **Prepare the fault**: arm the checkpoint in the target process, arm a duplicate-publish switch,
   or, for producer crashes with an outbox, start the relay with its gate closed (the barrier).
5. **Drive the workload** (`WorkloadDriver`): orders are sent one by one to the producer's HTTP API.
6. **Apply the fault** (`FaultSupervisor` or the driver for network cuts), verify it, recover.
7. **Observe** (`Observer`) until the system settles or the scenario's deadline passes.
8. **Snapshot** both databases and the broker (`SnapshotReader`, `BrokerProbe`).
9. **Evaluate** the invariants (`InvariantEvaluator`), classify (`OutcomeClassifier`), compare with
   the expectation, write `report.json` and `report.html`.
10. **Clean up** by label, or keep the resources (`--keep`, `--keep-on-failure`) and list them.

`compare` and `demo` repeat this for several scenario/strategy cells; every cell gets a fresh lab.

## Processes and transaction boundaries

```
             HTTP /orders                    publish (confirm, mandatory)
  runner  ─────────────────▶  producer  ──────────────────────────────┐   (naive-dual-write)
                               │  tx: orders (+ outbox)                │
                               ▼                                       ▼
                         producer DB ◀── relay ── publish ──▶ Toxiproxy ──▶ RabbitMQ ──▶ consumer
                                         (outbox strategies)                              │ tx: stock,
                                                                                          │ stock_movement
                                                                                          ▼ (+ processed_message)
                                                                                      consumer DB
```

- The producer commits the order (and, for outbox strategies, the outbox row) in one
  `TransactionTemplate` call. Only after that call returns does it report
  `producer.after-db-commit-before-publish`.
- The relay reads pending outbox rows in commit order, publishes each, waits for the publisher
  confirm, checks that the mandatory message was not returned, reports
  `relay.after-publisher-confirm-before-outbox-mark`, then marks the row `SENT`.
- The consumer acknowledges manually. The stock change, the movement row and (idempotent strategy)
  the `processed_message` row commit in one transaction; then it reports
  `consumer.after-business-commit-before-ack` and acknowledges.
- Producer and consumer data live in separate databases. The consumer's business tables and its
  deduplication table share the consumer database so they can share a transaction.

Killing one process never touches the others or the runner: each is a separate container and the
runner talks to them only over HTTP and the database ports.

## Checkpoints

A checkpoint is armed over the process's control API (`POST /control/checkpoints/arm`) for a given
occurrence. When the process reaches it, the thread records the arrival in memory, logs it and
blocks. The runner polls `GET /control/checkpoints`, sees the arrival, writes it to its own timeline
(flushed to `timeline.jsonl` immediately), sends SIGKILL through the Docker API, and verifies with
`docker inspect` that the container is not running and exited with code 137. Nothing waits for a
fixed time; see [ADR 0001](adr/0001-checkpoint-coordination.md).

## Network faults

Publishers reach RabbitMQ through a Toxiproxy proxy; the consumer connects directly. A `network-cut`
disables the proxy (closing connections and refusing new ones) when no publish is in flight, keeps it
disabled until a failed publish attempt proves the outage reached the publisher, records the outbox
backlog at that moment, and re-enables it.

## Run directory

```
.commitgap/runs/<run-id>/
  manifest.json      status, kept resources, children of a compare/demo run
  report.json        versioned report (schema v1)
  report.html        rendered from report.json
  timeline.jsonl     the runner's timeline, written as events happen
  logs/<container>.log
  cells/<cell-id>/   one directory per cell of a compare or demo run
```

## Extending to another application

Version 1 only drives its own demo. Testing an external application would need, at minimum: a way
for that application to announce checkpoints and wait (the control API contract), a mapping from its
data to order/event identities for the invariants, and access to its broker topology. CommitGap does
not inspect arbitrary backends from a connection string, and the documentation does not claim it can.
