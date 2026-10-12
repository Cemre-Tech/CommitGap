# ADR 0008: Relay workers with transaction-scoped row locks

Status: amended (version 0.1.0 development)

## Context
The original decision used one worker without claims or leases. Testing competing workers requires
mutual exclusion that survives process death and does not invent durable in-progress ownership.

## Decision
Keep one relay process, with 1..8 configurable worker threads. Each worker starts a transaction,
selects one pending row with `FOR UPDATE SKIP LOCKED`, publishes it with a bounded confirm wait,
updates its status and commits. Row locks are the ownership mechanism; PostgreSQL releases them on
transaction rollback or process disconnection. No lease clock or in-progress status is needed.

The publisher records results in independent `REQUIRES_NEW` transactions. The connection pool holds
up to 18 connections, enough for eight outer relay transactions and their eight logging transactions.

## Consequences
Publishing holds a database transaction open across broker I/O, so this is a small lab implementation,
not a throughput recommendation. Confirm waits, retries and checkpoint barriers are bounded. With
several workers, global ordering is not guaranteed. Independent relay process targeting and leases
remain outside the lab's scope.

`OutboxRelayIT` proves simultaneous publication of different rows, exclusion of a locked row and
retry after a rollback. `relay-lock-crash` verifies real SIGKILL recovery before publishing;
`relay-confirm-gap` still shows why row locks cannot make a broker publish and an outbox mark atomic.
