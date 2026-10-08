# ADR 0004: Queue depth from rabbitmqctl, not the management API

Status: accepted (version 0.1.0)

## Context
While building the observer, the RabbitMQ management API reported `messages: 0` for several seconds
while three messages were waiting (its statistics are sampled, by default every 5 s). Deciding
"settled" or "nothing pending" on that value would be wrong.

## Decision
Queue depth (ready, unacknowledged, dead-lettered) is read with `rabbitmqctl list_queues` inside the
broker container (about one second per call). Cumulative counters from the management API are shown
as sampled broker statistics, or "not measured" when absent, and never decide an outcome.

## Consequences
Observation polls are slower (about one per second) but reflect the broker's current state.
