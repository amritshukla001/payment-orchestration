package com.payflow.common.events;

/**
 * Event types published onto the payment.events topic.
 * Only PAYMENT_INITIATED is produced so far; the rest are the contract
 * the fraud/funds-auth/ledger/settlement services will produce in later phases.
 */
public enum PaymentEventType {
    PAYMENT_INITIATED,
    FRAUD_APPROVED,
    FRAUD_REJECTED,
    FUNDS_AUTHORIZED,
    FUNDS_AUTHORIZATION_FAILED,
    LEDGER_POSTED,
    PAYMENT_SETTLED,
    COMPENSATION_STARTED,
    PAYMENT_COMPENSATED
}
