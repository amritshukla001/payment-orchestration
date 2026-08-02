-- Stores the verified Stripe PaymentMethod ID for CARD payments when
-- Stripe verification is enabled (StripeCardTokenVerifier) -- an audit
-- trail only, never read by the saga; settlement stays simulated.
ALTER TABLE payments ADD COLUMN stripe_payment_method_id VARCHAR(255);
