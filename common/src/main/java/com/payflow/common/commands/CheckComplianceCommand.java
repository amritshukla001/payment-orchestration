package com.payflow.common.commands;

import com.payflow.common.enums.PaymentMethod;

import java.time.Instant;
import java.util.UUID;

public record CheckComplianceCommand(
        UUID paymentId,
        UUID payerAccount,
        UUID payeeAccount,
        long amountCents,
        String currency,
        PaymentMethod paymentMethod,
        Instant occurredAt
) {
}
