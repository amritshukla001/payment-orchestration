package com.paymentengine.complianceservice.api.dto;

import com.paymentengine.complianceservice.domain.RegulatoryReport;

import java.time.Instant;
import java.util.UUID;

public record RegulatoryReportResponse(
        UUID id,
        UUID paymentId,
        UUID payerAccount,
        UUID payeeAccount,
        long amountCents,
        String currency,
        Instant reportedAt
) {
    public static RegulatoryReportResponse from(RegulatoryReport report) {
        return new RegulatoryReportResponse(
                report.getId(),
                report.getPaymentId(),
                report.getPayerAccount(),
                report.getPayeeAccount(),
                report.getAmountCents(),
                report.getCurrency(),
                report.getReportedAt()
        );
    }
}
