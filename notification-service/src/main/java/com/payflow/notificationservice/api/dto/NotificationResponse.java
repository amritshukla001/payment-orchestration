package com.payflow.notificationservice.api.dto;

import com.payflow.notificationservice.domain.NotificationRecord;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID paymentId,
        UUID accountId,
        String recipient,
        String outcome,
        String message,
        Instant sentAt
) {
    public static NotificationResponse from(NotificationRecord record) {
        return new NotificationResponse(
                record.getId(),
                record.getPaymentId(),
                record.getAccountId(),
                record.getRecipient().name(),
                record.getOutcome().name(),
                record.getMessage(),
                record.getSentAt()
        );
    }
}
