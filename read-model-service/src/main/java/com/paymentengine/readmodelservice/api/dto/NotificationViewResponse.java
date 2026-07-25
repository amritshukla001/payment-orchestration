package com.paymentengine.readmodelservice.api.dto;

import com.paymentengine.readmodelservice.domain.NotificationView;

import java.time.Instant;
import java.util.UUID;

public record NotificationViewResponse(
        UUID id,
        UUID paymentId,
        UUID accountId,
        String recipient,
        String outcome,
        String message,
        Instant sentAt
) {
    public static NotificationViewResponse from(NotificationView view) {
        return new NotificationViewResponse(
                view.getId(),
                view.getPaymentId(),
                view.getAccountId(),
                view.getRecipient(),
                view.getOutcome(),
                view.getMessage(),
                view.getSentAt()
        );
    }
}
