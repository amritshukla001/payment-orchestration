package com.payflow.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Carries payer/payee so notification-service (a passive subscriber, not
 * something the orchestrator commands) can notify both parties without
 * needing to look anything up elsewhere.
 */
public record PaymentSettledEvent(UUID paymentId, UUID payerAccount, UUID payeeAccount, Instant occurredAt) {
}
