# ADR 0001: Checkpoint coordination over a control API

Status: accepted (version 0.1.0)

## Context
Faults must hit an exact transactional stage (after commit, after confirm, before ack). Killing a
process after a random or fixed delay does not tell which stage it was in, and a method call is not
proof that a commit happened.

## Decision
Each demo process exposes `/control/checkpoints`. The runner arms a checkpoint for an occurrence. The
code calls the checkpoint only after the stage completed (for example after `TransactionTemplate`
returned, or after a positive confirm for a message that was not returned). The thread records the
arrival in memory and blocks. The runner polls, sees the arrival, records it in its own timeline file,
then applies the fault. Arrivals are not stored in the business database, so recording them can
neither join nor influence a business transaction.

For producer crashes in outbox strategies, the relay starts with a closed gate and the runner opens it
only after the producer fault was handled, so the relay cannot publish the row early.

## Consequences
Faults are deterministic with respect to the stage; no sleeps are used for synchronisation. The demo
contains a small amount of test-only code (the control API). An external application would need to
implement the same contract.
