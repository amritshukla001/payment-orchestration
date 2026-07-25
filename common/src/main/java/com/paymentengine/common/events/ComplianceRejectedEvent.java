package com.paymentengine.common.events;

import java.time.Instant;
import java.util.UUID;

public record ComplianceRejectedEvent(UUID paymentId, String reason, Instant occurredAt) {
}
