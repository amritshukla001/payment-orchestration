package com.payflow.paymentengine.api.dto;

import com.payflow.paymentengine.domain.PaymentEngineAggregate;

import java.time.Instant;
import java.util.UUID;

public record PaymentEngineResponse(
        UUID paymentId,
        UUID payerAccount,
        UUID payeeAccount,
        long amountCents,
        String currency,
        String paymentMethod,
        String state,
        Instant updatedAt
) {
    public static PaymentEngineResponse from(PaymentEngineAggregate aggregate) {
        return new PaymentEngineResponse(
                aggregate.getPaymentId(),
                aggregate.getPayerAccount(),
                aggregate.getPayeeAccount(),
                aggregate.getAmountCents(),
                aggregate.getCurrency(),
                aggregate.getPaymentMethod(),
                aggregate.getState().name(),
                aggregate.getUpdatedAt()
        );
    }
}
