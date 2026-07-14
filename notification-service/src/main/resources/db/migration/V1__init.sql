CREATE TABLE notifications (
    id         UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    account_id UUID NOT NULL,
    recipient  VARCHAR(10) NOT NULL,
    outcome    VARCHAR(10) NOT NULL,
    message    TEXT NOT NULL,
    sent_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_payment ON notifications (payment_id);

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
