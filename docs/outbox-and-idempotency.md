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

1. lock one available `PENDING` row with `FOR UPDATE SKIP LOCKED` in a relay transaction
2. publish it (persistent, `mandatory`) and wait for the publisher confirm
3. check that the broker did not return it as unroutable — a confirm alone does not mean it reached a queue
4. `UPDATE outbox SET status = 'SENT' WHERE event_id = ?`, then commit and release the row lock

The row lock excludes concurrent relay workers while the publish is in flight. Killing a worker's
process rolls back its open database transaction and releases the lock. It cannot undo a broker
publish that already happened. With more than one worker, later rows can be published before earlier
ones; the lab does not promise global ordering. `competing-relays` runs three threads in one process;
`relay-lock-crash` tests process death before the locked event is published. Independent relay
process failover and leases are not implemented in the runner.

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

`publisher-confirm-gap` makes the distinction visible another way: the publisher has observed the
broker's positive confirm, then its process is killed before it can record or return that result.
The first publish is absent from `publish_log`, but the runner preserved its actual-stage checkpoint
and the consumer receives the event twice after retry. This is a process-memory failure, not an
injected lost network confirm. In `relay-confirm-gap`, by contrast, both confirmed publish results
are recorded. Publishing logs commit in their own `REQUIRES_NEW` transactions so rolling back the
relay transaction does not erase a result it already recorded.

`confirm-loss` additionally drops real downstream AMQP bytes on a warmed connection using Toxiproxy.
The upstream publish still reaches RabbitMQ: the directly connected consumer records the event's
delivery while the publisher's real confirm wait times out. The outbox retries after the response
path recovers, producing a duplicate delivery; the idempotent consumer absorbs it. The naive producer
does not retry and has one business effect despite its timeout. A confirm timeout means acceptance
is unknown to the publisher, not that the broker necessarily lost the event.

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
  failing the first transaction after its dedup insert and after all business writes. The
  `consumer-transaction-crash` scenario also SIGKILLs the real consumer after the stock and movement
  writes, before commit; redelivery applies the event once because every uncommitted write rolled back.
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
