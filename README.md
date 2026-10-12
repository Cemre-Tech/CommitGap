# CommitGap

**Test what happens between commit and delivery.**

A local fault-injection lab for dual writes, transactional outbox, and idempotent consumers.

*An open-source project by Cemre Tech.* · [Türkçe](README.tr.md)

---

If a backend operation is cut off between writing to its database and delivering its message, or
while a message is being reprocessed, what happens to the business records?

CommitGap answers that with measurements instead of diagrams. It runs a small order/stock system in
Docker, stops a process at an exact transactional point with SIGKILL (or cuts its broker link), lets
it recover, and then checks the producer and consumer databases event by event. The same workload
runs against three strategies, each in its own environment:

| Strategy | Producer | Consumer |
|---|---|---|
| `naive-dual-write` | commits the order, then publishes; no durable record of the pending event | applies every delivery |
| `transactional-outbox` | commits the order and an outbox row together; a separate relay publishes | applies every delivery |
| `outbox-idempotent` | same as above | records `(consumer_name, event_id)` in the same transaction as the stock change and skips duplicates |

All three use the same event ids, broker durability settings (quorum queue, persistent messages,
publisher confirms, mandatory publishing) and workload. The fragile variants fail only because of the
fault being tested.

## Requirements

- Java 21 or newer
- A running Docker engine with Linux containers (Docker Desktop on Windows/macOS, or Docker on Linux)
- Network access on first use, for Maven dependencies and the pinned container images

CommitGap installs nothing on your machine. On first use the launcher builds the project with the
included Maven Wrapper.

## Quick start

```bash
git clone https://github.com/Cemre-Tech/CommitGap.git
cd CommitGap
./commitgap doctor
./commitgap demo
```

On Windows use `.\commitgap.ps1 doctor` in PowerShell, or `commitgap.cmd doctor` in `cmd.exe`.

`doctor` checks Java, Docker, the build, the scenario files and the images. `demo` runs every bundled
scenario against every strategy (33 cells; applicable cells use real PostgreSQL, RabbitMQ,
Toxiproxy and demo processes) and prints the measured matrix. It takes several minutes; each cell
starts a fresh environment.

## Commands

```bash
./commitgap doctor
./commitgap demo                                     # all scenarios x all strategies
./commitgap demo --scenario crash-after-commit       # a subset
./commitgap scenarios list                           # list and validate scenario files
./commitgap run --scenario crash-after-commit --strategy outbox-idempotent
./commitgap compare --scenario duplicate-delivery    # one scenario, every strategy
./commitgap report --run <run-id>                    # regenerate the HTML from report.json
./commitgap cleanup --run <run-id>                   # remove that run's containers and network
```

Useful options: `--keep-on-failure` (keep and list the Docker resources of an inconclusive or
mismatching run), `--keep`, `--runs-dir DIR`, `--scenario ./my-file.yaml`.

Exit codes:

- `run`: 0 checks held, 1 violation measured, 2 invalid usage or measurement obstacle
- `demo` / `compare`: 0 every expected result verified, 1 an expectation was not matched,
  2 a measurement obstacle (inconclusive cell) or invalid usage

## Example

```text
$ ./commitgap compare --scenario duplicate-delivery
...
scenario            naive-dual-write                  transactional-outbox              outbox-idempotent
--------------------------------------------------------------------------------------------------------------------------
duplicate-delivery  VIOLATION_OBSERVED [matched]      VIOLATION_OBSERVED [matched]      CONSISTENT [matched]
```

The bracket says whether the measured outcome matched the scenario's prediction. A fragile strategy
that shows its predicted violation is *matched* and stays `VIOLATION_OBSERVED`: the demonstration
worked, the data did not stay correct.

A full `./commitgap demo` measured on one development machine (Windows 11, Docker Desktop 29.6.1;
7 min 49 s for the original six scenarios / 18 cells, exit code 0; the five newer scenarios below
are not included in this historical example):

```text
scenario            naive-dual-write                  transactional-outbox              outbox-idempotent
--------------------------------------------------------------------------------------------------------------------------
broker-outage       VIOLATION_OBSERVED [matched]      CONSISTENT [matched]              CONSISTENT [matched]
consumer-ack-gap    VIOLATION_OBSERVED [matched]      VIOLATION_OBSERVED [matched]      CONSISTENT [matched]
crash-after-commit  VIOLATION_OBSERVED [matched]      CONSISTENT [matched]              CONSISTENT [matched]
duplicate-delivery  VIOLATION_OBSERVED [matched]      VIOLATION_OBSERVED [matched]      CONSISTENT [matched]
happy-path          CONSISTENT [matched]              CONSISTENT [matched]              CONSISTENT [matched]
relay-confirm-gap   NOT_APPLICABLE [matched]          VIOLATION_OBSERVED [matched]      CONSISTENT [matched]
```

Your timings will differ; the outcomes should not, because faults are bound to checkpoints rather
than timing. If they do differ on your machine, the report shows why.

Every run writes `report.json` (versioned schema) and a self-contained `report.html` under
`.commitgap/runs/<run-id>/`: strategy comparison, invariant-by-invariant expected and measured
values with the event ids behind them, a checkpoint/fault/restart/delivery timeline, and the versions
of everything involved. The HTML has no scripts and loads nothing from the network.

## Scenarios

| Scenario | Fault | naive | outbox | outbox + idempotent |
|---|---|---|---|---|
| `happy-path` | none (control) | CONSISTENT | CONSISTENT | CONSISTENT |
| `crash-after-commit` | SIGKILL producer after its commit, before publishing | VIOLATION | CONSISTENT | CONSISTENT |
| `relay-confirm-gap` | SIGKILL relay after the broker confirm, before marking the row | not applicable | VIOLATION | CONSISTENT |
| `duplicate-delivery` | one event published three times, two consumer workers | VIOLATION | VIOLATION | CONSISTENT |
| `consumer-ack-gap` | SIGKILL consumer after its commit, before the ack | VIOLATION | VIOLATION | CONSISTENT |
| `broker-outage` | publisher-to-broker link cut for 5 orders, then restored | VIOLATION | CONSISTENT | CONSISTENT |
| `publisher-confirm-gap` | SIGKILL relay after the actual broker confirm, before recording its result | not applicable | VIOLATION | CONSISTENT |
| `confirm-loss` | real broker-to-publisher traffic dropped after warm-up; message accepted but publisher times out | CONSISTENT | VIOLATION | CONSISTENT |
| `consumer-transaction-crash` | SIGKILL consumer after business writes, before transaction commit | CONSISTENT | CONSISTENT | CONSISTENT |
| `competing-relays` | three relay workers compete for pending rows | CONSISTENT | CONSISTENT | CONSISTENT |
| `relay-lock-crash` | SIGKILL relay while it owns a row lock, before publishing | not applicable | CONSISTENT | CONSISTENT |

These are the scenarios' *expectations*. Each run measures the actual outcome and reports any
mismatch. A scenario file looks like this:

```yaml
schemaVersion: 1
id: crash-after-commit
description: Kill the producer after its database transaction commits, before it publishes.
workload: { seed: 42, orders: 20, quantityPerOrder: 1, initialStock: 100, rollbackEvery: 7 }
fault:
  target: producer
  checkpoint: producer.after-db-commit-before-publish
  action: kill
  occurrence: 5
recovery: { action: restart }
observation: { timeoutSeconds: 30 }
assertions: [committed-order-has-business-effect, stock-changed-once-per-event]
expectedByStrategy:
  naive-dual-write: VIOLATION_OBSERVED
  transactional-outbox: CONSISTENT
  outbox-idempotent: CONSISTENT
```

See [docs/scenarios.md](docs/scenarios.md) for the full format and what each scenario shows.

## How it measures

- **Faults are bound to checkpoints, not timing.** A process reports a checkpoint only after the
  stage really happened (the commit returned, the broker confirmed a routable message), then waits.
  The runner sees it, sends SIGKILL, and verifies exit code 137 before restarting the container.
- **Evidence is the databases.** Invariants compare event and order ids in the producer and consumer
  databases; one missing and one duplicated event cannot cancel out. Logs only explain.
- **Outcome and expectation are separate.** `CONSISTENT`, `VIOLATION_OBSERVED`, `INCONCLUSIVE`
  (not enough evidence: environment failure, checkpoint not reached, work still pending) and
  `NOT_APPLICABLE` describe the data; *matched / not matched / not verified* describes the demo.
- **Pending is not lost.** A missing effect with a pending outbox row or queued message at the
  deadline is `INCONCLUSIVE`, not a violation.

Each database is captured in its own read-only repeatable-read transaction. Stock, movement and
deduplication records share one database snapshot even when a writer commits between the queries.
This does not create an atomic snapshot shared across both databases and the broker.

The HTML report includes an event story linking the recorded checkpoint, fault, recovery and
per-event evidence. See [docs/reports.md](docs/reports.md) for how to interpret it.

Details: [docs/measurement.md](docs/measurement.md), [docs/architecture.md](docs/architecture.md),
[docs/outbox-and-idempotency.md](docs/outbox-and-idempotency.md), [decision records](docs/adr/README.md).

## Isolation and cleanup

Each run uses its own Docker network, container names (`commitgap-<run-id>-...`), labels, random
ports and generated credentials, so concurrent runs do not interfere. Resources are removed when a
run ends, fails or is interrupted. `cleanup` removes only resources labelled with the selected run id;
it never prunes Docker and never touches databases it did not create. Credentials are never written
to reports.

## Limits

- The Docker CLI drives the bundled demo. The optional `commitgap-adapter` Java SDK lets another
  application map its own run-scoped data to the same invariants and signal cooperative checkpoints.
  See [the standalone Spring example](examples/external-spring/README.md) and
  [integration contract](docs/external-applications.md). The application owns workload driving,
  stopping writers and recovery; the SDK does not analyse arbitrary backends or perform SIGKILL.
- Checks that hold in one run are not a general exactly-once guarantee for every failure.
- The seed makes ids and the workload reproducible, not wall-clock timing or scheduling.
- One relay process, with 1–8 worker threads (`workload.relayWorkers`, default 1) coordinated by
  database row locks. Independent relay processes and leases are not covered. Multiple workers do
  not guarantee global ordering. `broker-outage` cuts the whole publishing link while no publish is
  in flight. `confirm-loss` drops only downstream responses on a warmed connection and verifies both
  the real publisher timeout and consumer receipt. `publisher-confirm-gap` separately tests process
  state loss after a real positive confirm.
- No Maven, npm or Docker package and no GitHub release has been published. See
  [docs/release.md](docs/release.md) to build and package one.

## Development

```bash
./mvnw verify          # unit tests (no Docker)
./mvnw -Pit verify     # integration tests against real containers
./mvnw -Pdist -DskipTests package   # release zip in commitgap-cli/target/
```

Contributions are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md), the
[Code of Conduct](CODE_OF_CONDUCT.md) and the [security policy](SECURITY.md).

## License

MIT, see [LICENSE](LICENSE). Third-party components: [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
