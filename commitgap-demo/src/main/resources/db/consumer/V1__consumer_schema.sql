-- Consumer database. Kept separate from the producer database on purpose: the consumer's business
-- change and its deduplication record can share a transaction only because they live here together.

CREATE TABLE stock (
    sku      text    PRIMARY KEY,
    quantity integer NOT NULL
);

-- The business effect ledger. event_id is deliberately NOT unique: the non-idempotent consumers
-- must be able to apply an event twice, otherwise this table would quietly deduplicate for them.
CREATE TABLE stock_movement (
    id         bigserial   PRIMARY KEY,
    event_id   uuid        NOT NULL,
    order_id   uuid        NOT NULL,
    sku        text        NOT NULL,
    delta      integer     NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- Deduplication records of the idempotent consumer. Inserted with ON CONFLICT DO NOTHING in the same
-- transaction as the stock change.
CREATE TABLE processed_message (
    consumer_name text        NOT NULL,
    event_id      uuid        NOT NULL,
    processed_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (consumer_name, event_id)
);

-- Observation log, written on its own autocommit connection before and after processing.
-- outcome: APPLIED, DUPLICATE_SKIPPED, FAILED, or NULL when the process died in between.
CREATE TABLE delivery_log (
    id             bigserial   PRIMARY KEY,
    event_id       uuid,
    redelivered    boolean     NOT NULL,
    delivery_count integer,
    worker         text        NOT NULL,
    outcome        text,
    detail         text,
    received_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at    timestamptz
);
