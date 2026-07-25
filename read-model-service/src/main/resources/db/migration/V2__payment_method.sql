ALTER TABLE payment_view
    ADD COLUMN payment_method VARCHAR(16) NOT NULL DEFAULT 'NETBANKING';
ALTER TABLE payment_view
    ALTER COLUMN payment_method DROP DEFAULT;
