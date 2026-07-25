package com.paymentengine.common.commands;

import java.time.Instant;
import java.util.UUID;

public record SettleCommand(
        UUID paymentId,
        UUID payerAccount,
        UUID payeeAccount,
        long amountCents,
        String currency,
        Instant occurredAt
) {
}
