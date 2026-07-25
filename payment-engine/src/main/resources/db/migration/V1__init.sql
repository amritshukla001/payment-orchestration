CREATE TABLE payment_saga_state (
    payment_id    UUID PRIMARY KEY,
    payer_account UUID NOT NULL,
    amount_cents  BIGINT NOT NULL,
    currency      VARCHAR(3) NOT NULL,
    state         VARCHAR(20) NOT NULL,
    version       INT NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
