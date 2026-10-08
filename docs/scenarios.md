# Scenarios

A scenario is a YAML file describing a workload, at most one fault, the recovery, how long to
observe, which invariants to check, and what each strategy is expected to show. Scenario files are
data: nothing in them is executed, and the loader uses a safe constructor that refuses YAML tags.

## Format (schemaVersion 1)

```yaml
schemaVersion: 1                 # required; only 1 is supported
id: crash-after-commit           # required; lowercase letters, digits, single dashes
description: Kill the producer after its database transaction commits.
workload:
  seed: 42                       # required; fixes order ids, event ids and the rollback pattern
  orders: 20                     # required; 1..500
  quantityPerOrder: 1            # required; 1..1000
  initialStock: 100              # required; 0..10000000
  rollbackEvery: 7               # optional; every n-th order is rolled back by the producer (0 = none)
  consumerWorkers: 1             # optional; concurrent consumer threads, 1..8
fault:                           # optional; omit for a control scenario
  target: producer               # producer | relay | consumer | publisher | broker-link
  checkpoint: producer.after-db-commit-before-publish
  action: kill                   # kill | duplicate-publish | network-cut
  occurrence: 5                  # optional (default 1): the n-th time the checkpoint is reached
recovery:
  action: restart                # restart | restore-network | none
observation:
  timeoutSeconds: 30             # required; 5..600
  quietPeriodMillis: 1500        # optional; how long nothing may change before "settled"
assertions:                      # required; invariants to evaluate (see measurement.md)
  - committed-order-has-business-effect
  - stock-changed-once-per-event
expectedByStrategy:              # required; one entry per strategy
  naive-dual-write: VIOLATION_OBSERVED
  transactional-outbox: CONSISTENT
  outbox-idempotent: CONSISTENT
```

### Fault fields by action

| action | target | required | optional | recovery |
|---|---|---|---|---|
| `kill` | `producer`, `relay`, `consumer` | `checkpoint` (must belong to the target) | `occurrence` | `restart` or `none` |
| `duplicate-publish` | `publisher` | `copies` (2..10) | `occurrence` (n-th distinct event published) | `none` |
| `network-cut` | `broker-link` | `atOrder`, `ordersDuringFault` | — | `restore-network` |

Fields that make no sense for the chosen action are rejected (for example `copies` with `kill`).

### What the loader rejects

Every problem is reported at once, with the field path:

- duplicate keys, unknown fields at any level, wrong types (`orders: "20"`)
- a missing required field, an unsupported `schemaVersion`
- unknown checkpoints, actions, targets, recovery actions, invariants, strategies or outcomes
- a checkpoint of another process than the target, a recovery that cannot undo the fault
- an `occurrence` larger than the number of committed orders (the fault could never trigger)
- a network window that runs past the end of the workload
- `INCONCLUSIVE` as an expectation (it describes a measurement problem, not a behaviour)
- `NOT_APPLICABLE` for a strategy the fault applies to, or any other value for one it does not
- a file whose name does not match its `id` when referenced by id

Check files with `commitgap scenarios list` (exit code 2 if any is invalid). Pass a file path instead
of an id to run your own: `commitgap run --scenario ./my-scenario.yaml --strategy outbox-idempotent`.

### Reproducibility

The seed fixes order ids, event ids and which orders roll back, using `java.util.Random`, whose
algorithm is part of the Java specification. It does not fix wall-clock time, thread scheduling or
network latency. Reports record the CLI version, image references and ids, Docker version and demo
artifact hash so that differing results can be explained.

## Bundled scenarios

All bundled scenarios use the same workload: seed 42, 20 orders of quantity 1, initial stock 100,
every 7th order rolled back (orders 7 and 14), so 18 orders commit and the expected final stock is 82.

### `happy-path`

No fault. Every strategy must be `CONSISTENT`. This is the control: if it fails on a machine, the
lab is not working there and the other comparisons should not be trusted.

### `crash-after-commit`

`kill` the producer at `producer.after-db-commit-before-publish`, 5th occurrence, then restart it.

- **naive-dual-write → VIOLATION_OBSERVED.** The order is committed; the event existed only in the
  killed process's memory. Nothing durable records that it still has to be sent, so after the
  restart nobody sends it. The report names the exact event id that the producer was holding at the
  checkpoint as the missing effect.
- **outbox strategies → CONSISTENT.** The outbox row committed with the order. The relay is started
  behind a closed gate (barrier) so that it cannot publish the row before the producer is killed;
  once the kill is verified and the producer restarted, the gate opens and the relay delivers it.

### `relay-confirm-gap`

`kill` the relay at `relay.after-publisher-confirm-before-outbox-mark`, 5th occurrence, restart.

- **naive-dual-write → NOT_APPLICABLE.** There is no relay.
- **transactional-outbox → VIOLATION_OBSERVED.** The broker confirmed the message, the row is still
  `PENDING`, the restarted relay publishes the same event (same event id) again, and the
  non-idempotent consumer applies it twice.
- **outbox-idempotent → CONSISTENT.** The second delivery finds the `(consumer_name, event_id)` row
  and is skipped. See [outbox-and-idempotency.md](outbox-and-idempotency.md).

### `duplicate-delivery`

The publisher sends the 5th event three times with the same event id; two consumer workers run.

- **naive-dual-write, transactional-outbox → VIOLATION_OBSERVED** (three effects for one event).
- **outbox-idempotent → CONSISTENT** (one effect; the others are `DUPLICATE_SKIPPED`).

### `consumer-ack-gap`

`kill` the consumer at `consumer.after-business-commit-before-ack`, 5th occurrence, restart. The
broker redelivers the unacknowledged message.

- **naive-dual-write, transactional-outbox → VIOLATION_OBSERVED** (the redelivery applies again).
- **outbox-idempotent → CONSISTENT** (the redelivery is recognised and acknowledged without effect).

### `broker-outage`

Cut the publisher-to-broker link before order 8, keep it cut for orders 8–12, restore it.

- **naive-dual-write → VIOLATION_OBSERVED.** Each of the five orders commits and its publish fails
  with "connection refused"; the naive producer has no durable record, so the five events are never
  sent. CommitGap does not assume they come back by themselves; the report lists the five event ids.
- **outbox strategies → CONSISTENT.** The five rows wait in the outbox (the report records the
  backlog during the outage), the relay's attempts fail and are retried with capped backoff, and
  after the link returns all of them are delivered.

## Expected results are checked, not asserted

The `expectedByStrategy` entries are predictions. Every run measures the outcome from the databases
and then compares it with the prediction; a mismatch is reported (and makes `demo`/`compare` exit
with 1), it is never overwritten. The integration tests check the evidence behind each expectation,
for example that the missing event in `crash-after-commit` is the one held at the checkpoint.
