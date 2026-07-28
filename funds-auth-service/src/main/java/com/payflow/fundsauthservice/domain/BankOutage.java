package com.payflow.fundsauthservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Presence of a row means that bank's NetBanking gateway is down for
 * maintenance -- mirrors compliance-service's UpiRegistration.
 */
@Entity
@Table(name = "bank_outages")
public class BankOutage {

    @Id
    @Column(name = "bank_code")
    private String bankCode;

    @Column(name = "marked_down_at", nullable = false)
    private Instant markedDownAt;

    protected BankOutage() {
        // JPA
    }

    public BankOutage(String bankCode, Instant markedDownAt) {
        this.bankCode = bankCode;
        this.markedDownAt = markedDownAt;
    }

    public String getBankCode() {
        return bankCode;
    }

    public Instant getMarkedDownAt() {
        return markedDownAt;
    }
}
