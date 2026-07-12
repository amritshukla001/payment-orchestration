package com.payflow.fundsauthservice.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A simulated bank account. Lazily provisioned the first time a payer
 * account is referenced — this is a mock bank, not a real ledger, so there's
 * no onboarding flow to open one.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID accountId;

    @Column(name = "balance_cents", nullable = false)
    private long balanceCents;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
        // JPA
    }

    public Account(UUID accountId, long balanceCents, Instant now) {
        this.accountId = accountId;
        this.balanceCents = balanceCents;
        this.createdAt = now;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getBalanceCents() {
        return balanceCents;
    }

    public boolean debit(long amountCents) {
        if (balanceCents < amountCents) {
            return false;
        }
        balanceCents -= amountCents;
        return true;
    }

    public void credit(long amountCents) {
        balanceCents += amountCents;
    }
}
