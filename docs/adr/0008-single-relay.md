# ADR 0008: A single relay without claims or leases

Status: accepted (version 0.1.0)

## Context
Several relays polling one outbox need row claims or leases, and their crash recovery is its own set
of scenarios. The brief allows a single relay in version 1.

## Decision
Version 1 runs exactly one relay, reading pending rows in commit order without `FOR UPDATE SKIP
LOCKED` or leases. This is documented in the code and in the limits.

## Consequences
Multi-relay setups are not tested; adding them requires claim/lease logic and crash-recovery
scenarios for it.
