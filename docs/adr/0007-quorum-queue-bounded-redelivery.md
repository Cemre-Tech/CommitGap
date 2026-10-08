# ADR 0007: Quorum queue with a delivery limit; same broker settings for all strategies

Status: accepted (version 0.1.0)

## Context
Retries must be bounded and visible. A consumer that requeues a failing message forever hides the
failure. The fragile strategies must not be made to look worse by weaker broker settings.

## Decision
All strategies use the same topology: a durable direct exchange, a quorum queue with
`x-delivery-limit: 5` and a dead-letter exchange and queue, persistent messages, publisher confirms
(correlated), publisher returns and mandatory publishing. The consumer acknowledges manually. The
relay retries with capped exponential backoff and parks an outbox row after 50 attempts. Parked rows
and dead-lettered messages are reported as given-up work.

## Consequences
The only differences between strategies are where the pending event is recorded and whether the
consumer deduplicates.
