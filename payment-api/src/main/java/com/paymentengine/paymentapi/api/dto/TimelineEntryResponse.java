package com.payflow.paymentapi.api.dto;

import com.payflow.paymentapi.domain.OutboxEvent;
import java.time.Instant;

public record TimelineEntryResponse(
        String eventType,
        boolean published,
        Instant occurredAt
) {
    public static TimelineEntryResponse from(OutboxEvent event) {
        return new TimelineEntryResponse(event.getEventType(), event.isPublished(), event.getCreatedAt());
    }
}
