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
  relayWorkers: 1                # optional; relay worker threads in one process, 1..8
fault:                           # optional; omit for a control scenario
  target: producer               # producer | relay | consumer | publisher | broker-link
  checkpoint: producer.after-db-commit-before-publish
  action: kill                   # kill | duplicate-publish | network-cut | confirm-loss
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
| `confirm-loss` | `broker-link` | `atOrder` (2..orders, must commit) | — | `restore-network` |

Fields that make no sense for the chosen action are rejected (for example `copies` with `kill`).
`confirm-loss` requires one relay worker so the lab can isolate the event and hold its retry until
the downstream fault is removed. An earlier committed order must warm the publisher connection.

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

### `publisher-confirm-gap`

Kill the relay at `relay.after-broker-confirm-before-publish-result`, 5th occurrence, restart.
The checkpoint runs inside the publisher only after a positive broker confirm and the routability
check. The first publish result has not been recorded in `publish_log` or returned to the relay.
This reproduces loss of process memory after known broker acceptance; it does not inject a lost
confirm packet on the network. The runner's checkpoint evidence and two actual delivery records
establish acceptance of the first publish even though its publish log is absent.

- **naive-dual-write → NOT_APPLICABLE.** This checkpoint belongs to the relay publisher.
- **transactional-outbox → VIOLATION_OBSERVED.** The restarted relay republishes the pending event;
  both copies produce business effects.
- **outbox-idempotent → CONSISTENT.** The retry produces one recorded confirmed publish; both copies
  were delivered, but the dedup key permits one business effect.

### `confirm-loss`

Wait until the previous order was confirmed and delivered and the outbox has drained, then install
a Toxiproxy `timeout` toxic with `stream: downstream` and `timeout: 0` before order 5. This drops
real broker-to-publisher bytes on the established AMQP connection while upstream publication remains
enabled. The consumer connects directly to RabbitMQ and is unaffected by this toxic.

The runner keeps the toxic installed until the consumer's database records the target event's
delivery **and** the publisher logs a real `TIMEOUT`. Outbox variants wait at
`relay.after-publisher-timeout-before-retry` after that timeout, so no retry races the observation.
The runner removes the toxic and releases this barrier only after both facts are observed. Toxic
removal may close the affected connection; the publisher reconnects normally on subsequent attempts.
Cleanup also removes the toxic and releases the barrier if verification fails.

- **naive-dual-write → CONSISTENT.** The event reached the broker and produced one business effect,
  even though the producer's confirm wait timed out. The naive producer does not retry it. This
  demonstrates why a timeout alone cannot establish that the broker rejected or lost a message.
- **transactional-outbox → VIOLATION_OBSERVED.** The timeout leaves the row pending, and the relay
  retries the same event after recovery. Two deliveries produce two business effects.
- **outbox-idempotent → CONSISTENT.** Two deliveries, one business effect; the retry is skipped.

This is an actual network confirm-loss experiment, distinct from `publisher-confirm-gap`, which
kills the publisher after it has already observed a positive confirm.

### `consumer-transaction-crash`

Kill the consumer at `consumer.after-business-write-before-commit`, 5th occurrence, restart. The
stock decrement, movement insert and (when enabled) dedup insert have executed in the same still
uncommitted transaction. Killing the process closes the database connection and rolls them back.

**Every strategy → CONSISTENT.** RabbitMQ redelivers the unacknowledged message; the restarted
consumer applies it once. The idempotent consumer must apply it rather than skip it: the crashed
transaction's dedup insert vanished together with its business writes. Compare this with
`consumer-ack-gap`, where the first transaction committed and a second effect is possible.

### `competing-relays`

Three concurrent worker threads run in one relay process (`relayWorkers: 3`), with no injected fault.
Every worker locks one pending row with `FOR UPDATE SKIP LOCKED`, publishes it and marks it sent in
the same transaction. Other workers can publish different rows without taking an already locked row.
This does not preserve global publication order. PostgreSQL integration tests also hold two workers
inside their publishing calls simultaneously and verify they selected distinct events.

**Every strategy → CONSISTENT.** Both outbox strategies publish each committed event once in this
fault-free control. The naive strategy has no relay and ignores `relayWorkers`.

### `relay-lock-crash`

Kill the single relay worker at `relay.after-row-lock-before-publish`, 5th occurrence, restart.
The relay holds a PostgreSQL row lock inside an uncommitted transaction and has not sent that event
to RabbitMQ. The crash releases the lock and leaves the durable row pending for the restarted worker.

- **naive-dual-write → NOT_APPLICABLE.** There is no relay.
- **both outbox strategies → CONSISTENT.** The event is confirmed and applied exactly once after restart.

The lock crash scenario uses one worker so that the process kill cannot also interrupt another
worker after its publish. Separately managed relay processes, leases and multi-process failover
are outside the current lab; `relayWorkers` configures threads within its one relay container.

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
