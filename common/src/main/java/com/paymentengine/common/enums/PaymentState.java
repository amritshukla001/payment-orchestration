package com.paymentengine.common.enums;

/**
 * Lifecycle of a payment as it moves through the saga.
 * SETTLED and COMPENSATED are terminal states.
 */
public enum PaymentState {
    INITIATED,
    COMPLIANCE_CHECKED,
    FRAUD_CHECKED,
    AUTHORIZED,
    LEDGER_POSTED,
    SETTLED,
    COMPENSATING,
    COMPENSATED,
    FAILED
}
