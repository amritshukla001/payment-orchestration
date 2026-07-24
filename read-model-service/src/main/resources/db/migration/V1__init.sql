-- The CQRS read model: one denormalized view purpose-shaped for the
-- dashboard, built entirely from payment.events -- not a foreign-keyed
-- join over the write-side services' own tables, which live in their own
-- databases and are never queried directly from here.

CREATE TABLE payment_view (
    payment_id    UUID PRIMARY KEY,
    payer_account UUID NOT NULL,
    payee_account UUID NOT NULL,
    amount_cents  BIGINT NOT NULL,
    currency      VARCHAR(3) NOT NULL,
    state         VARCHAR(32) NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE ledger_entry_view (
    id             UUID PRIMARY KEY,
    payment_id     UUID NOT NULL,
    debit_account  UUID NOT NULL,
    credit_account UUID NOT NULL,
    amount_cents   BIGINT NOT NULL,
    posting_type   VARCHAR(16) NOT NULL,
    posted_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_ledger_entry_view_payment ON ledger_entry_view (payment_id, posted_at);

CREATE TABLE notification_view (
    id         UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    account_id UUID NOT NULL,
    recipient  VARCHAR(10) NOT NULL,
    outcome    VARCHAR(10) NOT NULL,
    message    TEXT NOT NULL,
    sent_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_notification_view_payment ON notification_view (payment_id, sent_at);

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
