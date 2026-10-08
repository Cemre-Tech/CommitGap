# Measurement

## Evidence

Business correctness is decided only from the rows in the producer and consumer databases, read
at the end of the observation window:

| Database | Table | Meaning |
|---|---|---|
| producer | `orders` | committed orders, each with the event id assigned by the workload |
| producer | `outbox` | outbox rows: `PENDING`, `SENT`, or `PARKED` after the bounded attempts |
| consumer | `stock` | the current stock |
| consumer | `stock_movement` | the business-effect ledger: one row per applied event (deliberately not unique) |
| consumer | `processed_message` | deduplication records of the idempotent consumer |

Two more tables are *explanatory* and are written on autocommit statements outside any business
transaction: `publish_log` (every publish attempt and its broker result) and `delivery_log` (every
delivery the consumer received, with its outcome or none if the process died). They feed the timeline
and the counters; they never decide whether an invariant holds. A log line is not proof of an effect.

Queue depth comes from `rabbitmqctl list_queues` in the broker container (current state). The
management API's `message_stats` (published, delivered, redelivered) are sampled statistics; they are
shown as broker counters when present and as "not measured" otherwise.

## Invariants

All checks work on identity sets — event ids and order ids — never on bare row counts. One missing
event and one duplicated event leave the row count and the stock unchanged; the identity checks still
catch both. A unit test demonstrates exactly that case.

| Invariant | PASS when | FAIL when | INCONCLUSIVE when |
|---|---|---|---|
| `committed-order-has-business-effect` | every committed order has a stock movement with its own event id | an order has no effect and nothing could still deliver it (no pending outbox row, empty queue); or ids disagree with the plan | an order has no effect but a pending outbox row or queued/unacked message could still deliver it |
| `stock-changed-once-per-event` | every event changed stock exactly once, by the ordered quantity, for its own order | an event has several movements, a wrong quantity or a wrong order | — |
| `rolled-back-order-has-no-effect` | no movement exists for an order that did not commit, or for an unknown event id | such a movement exists | an order planned to roll back committed (rollback path not exercised) |
| `stock-matches-committed-orders` | stock = initial − Σ committed quantities, and stock agrees with the movement ledger | the numbers differ, or stock and ledger disagree | stock is higher than expected while work is pending |
| `dedup-record-matches-business-effect` | processed-message ids equal the event ids with effects | a dedup record without an effect, or an effect without a record | — (NOT_APPLICABLE for non-idempotent consumers) |
| `no-pending-work-after-recovery` | no pending or parked outbox row, empty queue, nothing dead-lettered | rows were parked or messages dead-lettered (work was given up) | work was still pending at the deadline, or the broker could not be queried |

Timeouts are not treated as permanent loss: a missing effect with known pending work is
`INCONCLUSIVE`, and the report lists the pending ids separately from the unexplained ones.

## Counters kept apart

The report shows these separately because they answer different questions:

- **publish attempts / confirmed publishes** (publish log): how often the publisher tried and how often the broker confirmed
- **broker deliveries / redeliveries** (sampled broker statistics)
- **consumer attempts / attempts flagged redelivered** (delivery log)
- **business effects / distinct events with an effect / duplicate effects** (ledger)

A redelivery is not a duplicate effect unless the ledger shows a second movement.

## Outcome and expectation

The **outcome** is about the data:

| Outcome | Meaning |
|---|---|
| `CONSISTENT` | the selected checks held within the observation window |
| `VIOLATION_OBSERVED` | at least one check measurably failed |
| `INCONCLUSIVE` | not enough evidence: the environment failed, the checkpoint was not reached, the fault could not be verified, or work was still pending |
| `NOT_APPLICABLE` | the scenario's fault target does not exist in the strategy |

Rules, in order: not applicable → `NOT_APPLICABLE`; any measurement obstacle (including a fault not
applied as specified) → `INCONCLUSIVE`, even if a check failed; any failed check →
`VIOLATION_OBSERVED`; any inconclusive check → `INCONCLUSIVE`; otherwise `CONSISTENT`.

The **expectation** is about the demonstration: `MATCHED`, `NOT_MATCHED`, or `NOT_VERIFIED` when the
outcome is inconclusive. The naive strategy losing an event in `crash-after-commit` is `MATCHED` and
its outcome stays `VIOLATION_OBSERVED`; the report never turns a violation into a success.

## Exit codes

| Command | 0 | 1 | 2 |
|---|---|---|---|
| `run` | `CONSISTENT` | `VIOLATION_OBSERVED` | invalid usage, scenario not applicable, `INCONCLUSIVE` |
| `demo`, `compare` | every cell's expectation verified | some expectation not matched | any cell inconclusive (measurement obstacle), or invalid usage |
| `doctor` | ready | — | something required is missing |
| `scenarios list` | all files valid | — | a file is invalid |
| `report`, `cleanup` | done | — | unknown run, invalid id, or Docker unavailable / removal failed |

## Limits

- Passing checks in one run is not a distributed exactly-once guarantee for all failures. It shows
  that these checks held for this workload, this fault and this observation window.
- The seed does not make timing reproducible. Faults are bound to checkpoints, not to timing, so
  the measured outcome should not depend on timing; the integration tests check that it does not.
- Version 1 runs a single relay; claims, leases and multi-relay crash recovery are not covered.
- A network cut is applied while no publish is in flight. A cut that loses a confirm in flight is a
  different, ambiguous case and is not part of version 1.
- Only the bundled demo application can be tested.
