-- Presence of a row = that bank's NetBanking gateway is down for
-- maintenance. Mirrors compliance-service's upi_registrations table:
-- presence-based, no default row for any bank, cleared by deleting it.
CREATE TABLE bank_outages (
    bank_code      VARCHAR(16) PRIMARY KEY,
    marked_down_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
