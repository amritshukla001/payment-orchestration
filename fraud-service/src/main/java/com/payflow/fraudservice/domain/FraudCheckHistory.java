package com.payflow.fraudservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_check_history")
public class FraudCheckHistory {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "payer_account", nullable = false)
    private UUID payerAccount;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    protected FraudCheckHistory() {
        // JPA
    }

    public FraudCheckHistory(UUID id, UUID paymentId, UUID payerAccount, long amountCents, Instant checkedAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.payerAccount = payerAccount;
        this.amountCents = amountCents;
        this.checkedAt = checkedAt;
    }

    public UUID getPayerAccount() {
        return payerAccount;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }
}
