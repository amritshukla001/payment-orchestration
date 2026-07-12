CREATE TABLE accounts (
    account_id    UUID PRIMARY KEY,
    balance_cents BIGINT NOT NULL,
    version       INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE funds_reservations (
    payment_id   UUID PRIMARY KEY,
    account_id   UUID NOT NULL REFERENCES accounts(account_id),
    amount_cents BIGINT NOT NULL,
    status       VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
