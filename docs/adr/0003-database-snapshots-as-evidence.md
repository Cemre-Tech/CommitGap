# ADR 0003: Database snapshots are the evidence; checks use identities

Status: accepted (version 0.1.0)

## Context
Logs say what a process intended or attempted. They can be written before a transaction rolls back,
or be missing when a process dies. Row counts can hide a missing event behind a duplicate.

## Decision
Invariants are evaluated only on the producer and consumer database rows read after observation, and
they compare identity sets (event ids, order ids) against the seeded workload plan. Publish and
delivery logs are written on autocommit statements outside business transactions and are used only
to explain results (timeline, counters). The measured outcome and the scenario's expectation are kept
as separate fields.

## Consequences
A fragile strategy that shows its predicted failure is reported as VIOLATION_OBSERVED with expectation
MATCHED. Pending work at the deadline is INCONCLUSIVE, not loss.
