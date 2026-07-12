package com.payflow.ledgerservice.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only. Never updated or deleted — a correction is always a new
 * entry, never a mutation of history. This table is the actual source
 * of truth for fund movement, not payment-api's Payment row.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "debit_account", nullable = false)
    private UUID debitAccount;

    @Column(name = "credit_account", nullable = false)
    private UUID creditAccount;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(name = "posting_type", nullable = false, length = 10)
    private PostingType postingType;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;

    protected LedgerEntry() {
        // JPA
    }

    public LedgerEntry(UUID id, UUID paymentId, UUID debitAccount, UUID creditAccount,
                        long amountCents, PostingType postingType, Instant now) {
        this.id = id;
        this.paymentId = paymentId;
        this.debitAccount = debitAccount;
        this.creditAccount = creditAccount;
        this.amountCents = amountCents;
        this.postingType = postingType;
        this.postedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getDebitAccount() {
        return debitAccount;
    }

    public UUID getCreditAccount() {
        return creditAccount;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public PostingType getPostingType() {
        return postingType;
    }

    public Instant getPostedAt() {
        return postedAt;
    }
}
