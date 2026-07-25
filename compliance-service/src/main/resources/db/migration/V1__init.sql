-- Lazily provisioned on first sight, like funds-auth-service's accounts --
-- defaults to verified so existing demo flows work unchanged; a payment
-- from a specific account can be routed to a compliance rejection via
-- POST /api/compliance/accounts/{accountId}/flag.
CREATE TABLE kyc_records (
    account_id UUID PRIMARY KEY,
    verified   BOOLEAN NOT NULL DEFAULT true,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Presence of a row = the account is a registered UPI recipient. Absence
-- means a UPI-method payment to that payee is rejected -- mirrors "you can
-- only pay a registered VPA".
CREATE TABLE upi_registrations (
    account_id    UUID PRIMARY KEY,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Append-only. One row per payment whose amount crossed the AML reporting
-- threshold, recorded regardless of the eventual compliance verdict --
-- real CTR-style filing happens on the attempt, not just approved transfers.
CREATE TABLE regulatory_reports (
    id            UUID PRIMARY KEY,
    payment_id    UUID NOT NULL,
    payer_account UUID NOT NULL,
    payee_account UUID NOT NULL,
    amount_cents  BIGINT NOT NULL,
    currency      VARCHAR(3) NOT NULL,
    reported_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_regulatory_reports_payment ON regulatory_reports (payment_id);

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
