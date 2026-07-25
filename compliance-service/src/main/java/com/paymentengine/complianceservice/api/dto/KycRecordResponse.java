package com.paymentengine.complianceservice.api.dto;

import com.paymentengine.complianceservice.domain.KycRecord;

import java.time.Instant;
import java.util.UUID;

public record KycRecordResponse(UUID accountId, boolean verified, Instant updatedAt) {
    public static KycRecordResponse from(KycRecord record) {
        return new KycRecordResponse(record.getAccountId(), record.isVerified(), record.getUpdatedAt());
    }
}
