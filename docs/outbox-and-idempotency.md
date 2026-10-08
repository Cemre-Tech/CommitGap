# Why an outbox delivers duplicates, and how an idempotent consumer absorbs them

## The dual-write gap

A service that commits an order and then publishes `OrderCreated` performs two writes to two systems
with no shared transaction. Between them is a gap. If the process dies in that gap — after the commit,
before the publish — the order exists and the event does not. Nothing durable says that an event is
still owed, so nobody sends it. `crash-after-commit` measures exactly this.

## What the transactional outbox changes

The outbox moves the "an event is owed" fact into the same database transaction as the order:

```sql
BEGIN;
INSERT INTO orders (order_id, event_id, ...) VALUES (...);
INSERT INTO outbox (event_id, order_id, payload) VALUES (...);   -- status PENDING
COMMIT;
```

Either both rows exist or neither does. A separate relay then turns pending rows into messages:

1. read the oldest `PENDING` row
2. publish it (persistent, `mandatory`) and wait for the publisher confirm
3. check that the broker did not return it as unroutable — a confirm alone does not mean it reached a queue
4. `UPDATE outbox SET status = 'SENT' WHERE event_id = ?`

## Why this produces duplicates

Steps 2 and 4 are again two writes to two systems. If the relay dies after the broker confirmed the
message (step 2) and before it marks the row (step 4), the row is still `PENDING`. After a restart the
relay publishes it again, **with the same event id**, because the event id is stored in the row and
never regenerated. The broker now holds two copies. `relay-confirm-gap` reproduces this with a SIGKILL
at `relay.after-publisher-confirm-before-outbox-mark`.

There is no ordering of steps that avoids this. Marking before publishing would turn the duplicate
into a loss. The outbox trades "maybe never" for "at least once".

The same thing happens on the consumer side. A consumer that commits its stock change and dies before
acknowledging (`consumer-ack-gap`) gets the message redelivered by the broker. And publishers can send
duplicates for ordinary reasons, such as retrying after a lost confirm (`duplicate-delivery` forces
this deterministically).

A broker confirm means the broker took responsibility for the message. It does not mean a consumer
processed it. Delivery attempts and business effects are therefore measured separately.

## The idempotent consumer

The `outbox-idempotent` consumer records, in the same transaction as the business change, that it
processed the event:

```sql
BEGIN;
INSERT INTO processed_message (consumer_name, event_id) VALUES ('stock-consumer', ?)
  ON CONFLICT DO NOTHING;                 -- 1 row: first time; 0 rows: already processed
-- only if 1 row was inserted:
UPDATE stock SET quantity = quantity - ? WHERE sku = ?;
INSERT INTO stock_movement (event_id, order_id, sku, delta) VALUES (...);
COMMIT;
-- then: basicAck
```

Properties this gives, each covered by a test against PostgreSQL:

- **No check-then-act race.** The consumer never asks "have I seen this?" and then acts. The unique
  key `(consumer_name, event_id)` decides. When two workers handle the same event at the same time,
  the second `INSERT` waits on the key until the first transaction ends, then inserts nothing, and the
  second worker skips the business change. `StockLedgerIT` holds the first transaction open, verifies
  in `pg_stat_activity` that the second is blocked on a lock, and releases it.
- **A failed transaction does not count as processed.** If the business change fails, the dedup row
  rolls back with it; the waiting duplicate (or a redelivery) then applies the event. Tested by
  failing the first transaction after its dedup insert.
- **Acknowledge only after commit.** The consumer acknowledges after its transaction committed, or
  after it found the dedup row of an earlier commit. A crash before the acknowledgement is safe: the
  redelivery is recognised.
- **Bounded redelivery.** On a failed transaction the message is returned to the queue; the quorum
  queue's `x-delivery-limit` (5) bounds how often, after which the message is dead-lettered and
  reported. There is no endless requeue loop.

## Why the fragile variants are not quietly safe

The non-idempotent consumers write to the same `stock_movement` table, whose `event_id` column is
deliberately **not** unique. If it were, the table itself would deduplicate and the comparison would
show nothing. The naive and outbox strategies also use the same event ids, the same durable queue,
persistent messages, publisher confirms and mandatory publishing as the idempotent one; the only
difference is where the pending event is recorded and whether the consumer deduplicates.

## What this does not claim

The idempotent consumer makes the *stock change* happen once per event id in these scenarios. It is not
a general exactly-once guarantee: effects outside the consumer's database transaction (an email, a
call to another service) would need their own idempotency.
