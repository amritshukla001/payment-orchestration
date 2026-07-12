-- Append-only. No UPDATE or DELETE ever runs against this table —
-- a correction is a new offsetting entry, never a mutation of history.
CREATE TABLE ledger_entries (
    id            UUID PRIMARY KEY,
    payment_id    UUID NOT NULL,
    debit_account UUID NOT NULL,
    credit_account UUID NOT NULL,
    amount_cents  BIGINT NOT NULL,
    posting_type  VARCHAR(10) NOT NULL,
    posted_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_entries_payment ON ledger_entries (payment_id, posted_at);

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
