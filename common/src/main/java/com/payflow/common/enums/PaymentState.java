package com.payflow.common.enums;

/**
 * Lifecycle of a payment as it moves through the saga.
 * SETTLED and COMPENSATED are terminal states. AWAITING_STEP_UP is a
 * CARD-only pause between FRAUD_CHECKED and AUTHORIZED, waiting on an
 * external confirmation (see payment-engine's StepUpController) or a
 * scheduled timeout.
 */
public enum PaymentState {
    INITIATED,
    COMPLIANCE_CHECKED,
    FRAUD_CHECKED,
    AWAITING_STEP_UP,
    AUTHORIZED,
    LEDGER_POSTED,
    SETTLED,
    COMPENSATING,
    COMPENSATED,
    FAILED
}
