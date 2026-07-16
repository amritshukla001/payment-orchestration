package com.payflow.orchestrator.api.dto;

import com.payflow.orchestrator.domain.PaymentSagaState;

import java.time.Instant;
import java.util.UUID;

public record SagaResponse(
        UUID paymentId,
        UUID payerAccount,
        UUID payeeAccount,
        long amountCents,
        String currency,
        String state,
        Instant updatedAt
) {
    public static SagaResponse from(PaymentSagaState saga) {
        return new SagaResponse(
                saga.getPaymentId(),
                saga.getPayerAccount(),
                saga.getPayeeAccount(),
                saga.getAmountCents(),
                saga.getCurrency(),
                saga.getState().name(),
                saga.getUpdatedAt()
        );
    }
}
