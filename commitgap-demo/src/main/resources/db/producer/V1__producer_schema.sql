-- Producer database. The same schema is used by every strategy; the naive strategy simply never
-- writes to the outbox table.

CREATE TABLE orders (
    order_id   uuid        PRIMARY KEY,
    event_id   uuid        NOT NULL UNIQUE,
    sku        text        NOT NULL,
    quantity   integer     NOT NULL CHECK (quantity > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- Written in the same transaction as the order (outbox strategies only).
-- status: PENDING until the relay received a positive publisher confirm for a routable message,
-- SENT afterwards, PARKED when the relay gave up after its bounded number of attempts.
CREATE TABLE outbox (
    seq        bigserial   PRIMARY KEY,
    event_id   uuid        NOT NULL UNIQUE,
    order_id   uuid        NOT NULL REFERENCES orders (order_id),
    payload    text        NOT NULL,
    status     text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'PARKED')),
    attempts   integer     NOT NULL DEFAULT 0,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    sent_at    timestamptz
);

CREATE INDEX outbox_pending_idx ON outbox (seq) WHERE status = 'PENDING';

-- Observation log, written on its own autocommit connection outside any business transaction.
-- Explains what happened; never used as evidence of a business effect.
CREATE TABLE publish_log (
    id        bigserial   PRIMARY KEY,
    event_id  uuid        NOT NULL,
    publisher text        NOT NULL,
    result    text        NOT NULL,
    detail    text,
    at        timestamptz NOT NULL DEFAULT clock_timestamp()
);
