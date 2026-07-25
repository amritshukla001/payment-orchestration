package com.payflow.settlementservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per captured payment — the settlement's own record that this
 * payment has been confirmed captured, distinct from the ledger's
 * double-entry postings. Its existence is what makes a redelivered
 * SETTLE command a safe no-op.
 */
@Entity
@Table(name = "settlements")
public class Settlement {

    @Id
    private UUID paymentId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected Settlement() {
        // JPA
    }

    public Settlement(UUID paymentId, long amountCents, Instant capturedAt) {
        this.paymentId = paymentId;
        this.amountCents = amountCents;
        this.capturedAt = capturedAt;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }
}
