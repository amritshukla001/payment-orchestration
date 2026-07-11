package com.payflow.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload published to Kafka when a payment is first accepted.
 * This is the contract the saga orchestrator (a later phase) consumes.
 */
public record PaymentInitiatedEvent(
        UUID paymentId,
        UUID payerAccount,
        UUID payeeAccount,
        long amountCents,
        String currency,
        Instant occurredAt
) {
}
