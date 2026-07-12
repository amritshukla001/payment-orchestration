package com.payflow.common.events;

import java.time.Instant;
import java.util.UUID;

public record FundsAuthorizedEvent(UUID paymentId, Instant occurredAt) {
}
