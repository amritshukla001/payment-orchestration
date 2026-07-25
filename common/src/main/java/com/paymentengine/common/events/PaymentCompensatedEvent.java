package com.paymentengine.common.events;

import java.time.Instant;
import java.util.UUID;

public record PaymentCompensatedEvent(UUID paymentId, UUID payerAccount, Instant occurredAt) {
}
