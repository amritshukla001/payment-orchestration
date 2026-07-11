package com.payflow.paymentapi.api.dto;

import com.payflow.paymentapi.domain.Payment;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID payerAccount,
        UUID payeeAccount,
        long amountCents,
        String currency,
        String state,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getPayerAccount(),
                payment.getPayeeAccount(),
                payment.getAmountCents(),
                payment.getCurrency(),
                payment.getState().name(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
