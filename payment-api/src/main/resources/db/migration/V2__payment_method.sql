-- Payments now carry which method the payer used to fund them -- compliance
-- requirements differ by method (e.g. UPI requires a registered payee).
-- Backfilled with NETBANKING for any pre-existing rows -- dev data, not a
-- production migration, same precedent as saga-orchestrator's V2.
ALTER TABLE payments
    ADD COLUMN payment_method VARCHAR(16) NOT NULL DEFAULT 'NETBANKING';

ALTER TABLE payments
    ALTER COLUMN payment_method DROP DEFAULT;
