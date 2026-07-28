package com.payflow.common.events;

/**
 * Event types published onto the payment.events topic.
 * Only PAYMENT_INITIATED is produced so far; the rest are the contract
 * the fraud/funds-auth/ledger/settlement services will produce in later phases.
 */
public enum PaymentEventType {
    PAYMENT_INITIATED,
    COMPLIANCE_APPROVED,
    COMPLIANCE_REJECTED,
    FRAUD_APPROVED,
    FRAUD_REJECTED,
    STEP_UP_REQUIRED,
    FUNDS_AUTHORIZED,
    FUNDS_AUTHORIZATION_FAILED,
    LEDGER_POSTED,
    PAYMENT_SETTLED,
    LEDGER_FINALIZED,
    PAYMENT_FAILED,
    SETTLEMENT_DECLINED,
    COMPENSATION_STARTED,
    LEDGER_REVERSED,
    FUNDS_RELEASED,
    PAYMENT_COMPENSATED,
    NOTIFICATION_SENT
}
