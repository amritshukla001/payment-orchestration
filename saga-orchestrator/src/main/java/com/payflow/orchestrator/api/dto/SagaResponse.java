package com.payflow.orchestrator.api.dto;

import com.payflow.orchestrator.domain.PaymentSagaAggregate;

import java.time.Instant;
import java.util.UUID;

public record SagaResponse(
        UUID paymentId,
        UUID payerAccount,
        UUID payeeAccount,
        long amountCents,
        String currency,
        String paymentMethod,
        String state,
        Instant updatedAt
) {
    public static SagaResponse from(PaymentSagaAggregate saga) {
        return new SagaResponse(
                saga.getPaymentId(),
                saga.getPayerAccount(),
                saga.getPayeeAccount(),
                saga.getAmountCents(),
                saga.getCurrency(),
                saga.getPaymentMethod(),
                saga.getState().name(),
                saga.getUpdatedAt()
        );
    }
}
