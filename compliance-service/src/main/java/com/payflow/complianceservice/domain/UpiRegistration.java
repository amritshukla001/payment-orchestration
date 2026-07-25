package com.payflow.complianceservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Presence of a row means the account is a registered UPI recipient --
 * mirrors "you can only pay a registered VPA". Absence (the default for
 * any account never explicitly registered) means a UPI-method payment to
 * that payee is rejected by UpiDirectoryRule.
 */
@Entity
@Table(name = "upi_registrations")
public class UpiRegistration {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    protected UpiRegistration() {
        // JPA
    }

    public UpiRegistration(UUID accountId, Instant registeredAt) {
        this.accountId = accountId;
        this.registeredAt = registeredAt;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }
}
