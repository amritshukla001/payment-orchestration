package com.payflow.common.events;

import java.time.Instant;
import java.util.UUID;

public record LedgerPostedEvent(UUID paymentId, Instant occurredAt) {
}
