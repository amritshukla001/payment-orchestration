package com.payflow.common.events;

import java.time.Instant;
import java.util.UUID;

public record PaymentSettledEvent(UUID paymentId, Instant occurredAt) {
}
