package com.payflow.common.events;

import java.time.Instant;
import java.util.UUID;

public record FraudRejectedEvent(UUID paymentId, String reason, Instant occurredAt) {
}
