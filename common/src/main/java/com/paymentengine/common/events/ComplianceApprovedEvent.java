package com.paymentengine.common.events;

import java.time.Instant;
import java.util.UUID;

public record ComplianceApprovedEvent(UUID paymentId, Instant occurredAt) {
}
