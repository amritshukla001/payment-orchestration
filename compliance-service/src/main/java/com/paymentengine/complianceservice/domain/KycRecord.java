package com.payflow.complianceservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Lazily provisioned on first sight, like funds-auth-service's Account --
 * defaults to verified so a brand-new account can transact immediately.
 * A specific account is routed to a compliance rejection by explicitly
 * flagging it (POST /api/compliance/accounts/{accountId}/flag), the same
 * "clear, deliberate trigger" pattern the project already uses elsewhere
 * (e.g. settlement's $9k-$10k decline range).
 */
@Entity
@Table(name = "kyc_records")
public class KycRecord {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(nullable = false)
    private boolean verified;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KycRecord() {
        // JPA
    }

    public KycRecord(UUID accountId, boolean verified, Instant updatedAt) {
        this.accountId = accountId;
        this.verified = verified;
        this.updatedAt = updatedAt;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified, Instant updatedAt) {
        this.verified = verified;
        this.updatedAt = updatedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
