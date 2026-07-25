package com.paymentengine.common.events;

import java.time.Instant;
import java.util.UUID;

public record FraudApprovedEvent(UUID paymentId, Instant occurredAt) {
}
