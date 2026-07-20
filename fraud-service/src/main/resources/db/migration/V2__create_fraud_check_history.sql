CREATE TABLE fraud_check_history (
    id            UUID PRIMARY KEY,
    payment_id    UUID NOT NULL,
    payer_account UUID NOT NULL,
    amount_cents  BIGINT NOT NULL,
    checked_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_fraud_check_history_payer_time ON fraud_check_history(payer_account, checked_at);
