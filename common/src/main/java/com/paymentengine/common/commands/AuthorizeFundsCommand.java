package com.paymentengine.common.commands;

import java.time.Instant;
import java.util.UUID;

public record AuthorizeFundsCommand(
        UUID paymentId,
        UUID payerAccount,
        long amountCents,
        String currency,
        Instant occurredAt
) {
}
