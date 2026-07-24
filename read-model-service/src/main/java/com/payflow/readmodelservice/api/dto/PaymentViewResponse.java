package com.payflow.readmodelservice.api.dto;

import com.payflow.readmodelservice.domain.PaymentView;

import java.time.Instant;
import java.util.UUID;

public record PaymentViewResponse(
        UUID paymentId,
        UUID payerAccount,
        UUID payeeAccount,
        long amountCents,
        String currency,
        String state,
        Instant updatedAt
) {
    public static PaymentViewResponse from(PaymentView view) {
        return new PaymentViewResponse(
                view.getPaymentId(),
                view.getPayerAccount(),
                view.getPayeeAccount(),
                view.getAmountCents(),
                view.getCurrency(),
                view.getState().name(),
                view.getUpdatedAt()
        );
    }
}
