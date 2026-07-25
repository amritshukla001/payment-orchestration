package com.paymentengine.readmodelservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A denormalized copy of one ledger posting, projected from an enriched
 * LEDGER_POSTED/LEDGER_FINALIZED/LEDGER_REVERSED event -- not read from
 * ledger-service's own ledger_entries table.
 */
@Entity
@Table(name = "ledger_entry_view")
public class LedgerEntryView {

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

    @Column(name = "posting_type", nullable = false, length = 16)
    private String postingType;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    protected LedgerEntryView() {
        // JPA
    }

    public LedgerEntryView(UUID id, UUID paymentId, UUID debitAccount, UUID creditAccount,
                            long amountCents, String postingType, Instant postedAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.debitAccount = debitAccount;
        this.creditAccount = creditAccount;
        this.amountCents = amountCents;
        this.postingType = postingType;
        this.postedAt = postedAt;
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

    public String getPostingType() {
        return postingType;
    }

    public Instant getPostedAt() {
        return postedAt;
    }
}
