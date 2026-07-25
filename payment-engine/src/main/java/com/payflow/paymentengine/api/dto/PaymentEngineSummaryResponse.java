package com.payflow.paymentengine.api.dto;

import com.payflow.paymentengine.domain.PaymentEngineSummary;

import java.time.Instant;
import java.util.UUID;

public record PaymentEngineSummaryResponse(
        UUID paymentId,
        String summary,
        String source,
        Instant generatedAt
) {
    public static PaymentEngineSummaryResponse from(PaymentEngineSummary summary) {
        return new PaymentEngineSummaryResponse(
                summary.getPaymentId(),
                summary.getSummary(),
                summary.getSource().name(),
                summary.getGeneratedAt()
        );
    }
}
