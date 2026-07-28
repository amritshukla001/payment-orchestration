package com.payflow.fundsauthservice.rules;

import com.payflow.common.enums.PaymentMethod;

import java.util.UUID;

/** Plain-data input to FundsRule, mirroring CheckComplianceCommand's role for ComplianceRule. */
public record FundsAuthorizationContext(
        UUID accountId,
        long amountCents,
        long currentBalanceCents,
        PaymentMethod paymentMethod,
        String bankCode
) {
}
