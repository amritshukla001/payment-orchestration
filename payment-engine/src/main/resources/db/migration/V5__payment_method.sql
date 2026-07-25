-- Carries the same immutable-on-sequence-0 treatment as payer_account,
-- payee_account, amount_cents, and currency -- payment method never
-- changes after PAYMENT_INITIATED, so every later row leaves it null.
ALTER TABLE payment_saga_events
    ADD COLUMN payment_method VARCHAR(16);
