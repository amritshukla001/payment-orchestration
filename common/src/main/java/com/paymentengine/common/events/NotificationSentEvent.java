package com.paymentengine.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by notification-service after it records a notification --
 * lets a denormalized read model project notification history without
 * calling back into notification-service. Nothing in the saga reacts to
 * this; it exists solely for the read side.
 */
public record NotificationSentEvent(
        UUID id,
        UUID paymentId,
        UUID accountId,
        String recipient,
        String outcome,
        String message,
        Instant sentAt
) {
}
