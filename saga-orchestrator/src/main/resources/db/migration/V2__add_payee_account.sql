-- The orchestrator needs the payee account to build PostLedgerCommand,
-- which wasn't foreseen when V1 only tracked payer/amount/currency.
-- Backfilled with a placeholder zero UUID for any pre-existing rows —
-- acceptable here since this is dev data, not a production migration.
ALTER TABLE payment_saga_state
    ADD COLUMN payee_account UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000';

ALTER TABLE payment_saga_state
    ALTER COLUMN payee_account DROP DEFAULT;
