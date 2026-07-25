package com.paymentengine.orchestrator.api.dto;

import com.paymentengine.orchestrator.domain.SagaSummary;

import java.time.Instant;
import java.util.UUID;

public record SagaSummaryResponse(
        UUID paymentId,
        String summary,
        String source,
        Instant generatedAt
) {
    public static SagaSummaryResponse from(SagaSummary summary) {
        return new SagaSummaryResponse(
                summary.getPaymentId(),
                summary.getSummary(),
                summary.getSource().name(),
                summary.getGeneratedAt()
        );
    }
}
