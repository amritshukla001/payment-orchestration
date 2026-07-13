CREATE TABLE settlements (
    payment_id   UUID PRIMARY KEY,
    amount_cents BIGINT NOT NULL,
    captured_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
