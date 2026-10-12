# Reading the event story

Every run in the self-contained HTML report includes an **Event story**. It is a
reading guide built from the saved version 1 JSON report, so regenerating HTML
also adds the guide to previously saved reports. No new report schema fields,
network assets, or scripts are required for the story.

The workload also records `relayWorkers` (1–8). It is optional in schema v1:
reports saved before relay concurrency was added still validate, and the HTML
renderer displays their original single-worker default.

The story follows four recorded stages: checkpoint, fault action, recovery, and
the end of observation. A configured restart does not become a completed
recovery in the story unless the report has a recovery timestamp. An unverified
fault or failed recovery keeps its recorded status and problem.

When the checkpoint contains an event id, the story shows publish, delivery,
and business-effect records with that exact id. It does not assign another
event's records or run-wide counts to the checkpoint event. Publish and delivery
records describe attempts; business-effect records are derived from the final
stock movement snapshot. An absent attempt record alone does not prove that a
message was lost.

The database findings keep invariant explanations and evidence values, including
affected event ids, repeated-effect counts, pending outbox ids, and stock values.
Evidence values remain labelled as stored in JSON; they are not all event ids.
For example, `duplicatedEventIds` contains an event id followed by its movement
count, while `values` contains stock arithmetic. Unknown evidence keys are also
shown, allowing new scenarios and adapters to supply findings without a special
story template.

A short strategy explanation describes the protection available by design:

- `naive-dual-write`: separate commit and publish, with no durable outbox retry.
- `transactional-outbox`: atomic order/outbox commit and relay retries; repeated
  publication or delivery can still produce repeated business effects.
- `outbox-idempotent`: outbox retries plus an atomic event-id deduplication record
  and business effect in the consumer.

These descriptions are not conclusions about a particular run. Read its
invariants and measured outcome to assess the mechanism. **Expectation matched**
can accompany **VIOLATION_OBSERVED**. **INCONCLUSIVE** does not establish permanent
loss or correctness, and a missing snapshot produces no invented effect count.
Checks that hold in one experiment are not a general exactly-once guarantee.
