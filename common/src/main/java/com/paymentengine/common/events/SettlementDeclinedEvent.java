package com.payflow.common.events;

import java.time.Instant;
import java.util.UUID;

public record SettlementDeclinedEvent(UUID paymentId, String reason, Instant occurredAt) {
}
