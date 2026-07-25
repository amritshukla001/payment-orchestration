package com.paymentengine.paymentapi.api.dto;

import com.paymentengine.paymentapi.domain.Payment;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID payerAccount,
        UUID payeeAccount,
        long amountCents,
        String currency,
        String paymentMethod,
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
                payment.getPaymentMethod().name(),
                payment.getState().name(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
