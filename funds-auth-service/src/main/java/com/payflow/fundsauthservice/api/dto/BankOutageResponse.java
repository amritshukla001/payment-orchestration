package com.payflow.fundsauthservice.api.dto;

import com.payflow.fundsauthservice.domain.BankOutage;

import java.time.Instant;

public record BankOutageResponse(String bankCode, boolean down, Instant markedDownAt) {
    public static BankOutageResponse down(BankOutage outage) {
        return new BankOutageResponse(outage.getBankCode(), true, outage.getMarkedDownAt());
    }

    public static BankOutageResponse up(String bankCode) {
        return new BankOutageResponse(bankCode, false, null);
    }
}
