package com.payflow.complianceservice.api.dto;

import com.payflow.complianceservice.domain.UpiRegistration;

import java.time.Instant;
import java.util.UUID;

public record UpiRegistrationResponse(UUID accountId, Instant registeredAt) {
    public static UpiRegistrationResponse from(UpiRegistration registration) {
        return new UpiRegistrationResponse(registration.getAccountId(), registration.getRegisteredAt());
    }
}
