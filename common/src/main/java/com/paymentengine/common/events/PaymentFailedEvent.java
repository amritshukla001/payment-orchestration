package com.payflow.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the orchestrator whenever it moves a payment to FAILED,
 * regardless of which specific upstream rejection caused it (fraud,
 * insufficient funds, ...). Downstream consumers that only care about
 * "did this payment fail" -- like notification-service -- can watch this
 * single event instead of every specific failure type.
 */
public record PaymentFailedEvent(UUID paymentId, UUID payerAccount, String reason, Instant occurredAt) {
}
