-- Event-sources the saga aggregate: payment_saga_state mutated a
-- current-value `state` column in place; payment_saga_events is an
-- append-only log of every transition instead, with `state` computed by
-- folding over it on read (see PaymentSagaAggregate.replay()). Dropping
-- the old table loses whatever's in it -- acceptable here, same as V2's
-- precedent, since this is dev data, not a production migration.

CREATE TABLE payment_saga_events (
    id              UUID PRIMARY KEY,
    payment_id      UUID NOT NULL,
    sequence_number INT NOT NULL,
    event_type      VARCHAR(32) NOT NULL,
    to_state        VARCHAR(20) NOT NULL,
    payer_account   UUID,
    payee_account   UUID,
    amount_cents    BIGINT,
    currency        VARCHAR(3),
    occurred_at     TIMESTAMPTZ NOT NULL,
    UNIQUE (payment_id, sequence_number)
);
CREATE INDEX idx_payment_saga_events_payment ON payment_saga_events (payment_id, sequence_number);

DROP TABLE payment_saga_state;
