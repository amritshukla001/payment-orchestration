CREATE TABLE payments (
    id              UUID PRIMARY KEY,
    idempotency_key VARCHAR(64) UNIQUE NOT NULL,
    payer_account   UUID NOT NULL,
    payee_account   UUID NOT NULL,
    amount_cents    BIGINT NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    state           VARCHAR(20) NOT NULL,
    version         INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
    id           UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type   VARCHAR(40) NOT NULL,
    payload      TEXT NOT NULL,
    published    BOOLEAN NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (published, created_at) WHERE NOT published;
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_id, created_at);
