package com.payflow.complianceservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only, like ledger-service's LedgerEntry -- one row per payment
 * whose amount crossed the AML reporting threshold, recorded regardless of
 * the eventual compliance verdict. Real CTR-style filing happens on the
 * attempt, not just on approved transfers.
 */
@Entity
@Table(name = "regulatory_reports")
public class RegulatoryReport {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "payer_account", nullable = false)
    private UUID payerAccount;

    @Column(name = "payee_account", nullable = false)
    private UUID payeeAccount;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    protected RegulatoryReport() {
        // JPA
    }

    public RegulatoryReport(UUID id, UUID paymentId, UUID payerAccount, UUID payeeAccount,
                             long amountCents, String currency, Instant reportedAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.payerAccount = payerAccount;
        this.payeeAccount = payeeAccount;
        this.amountCents = amountCents;
        this.currency = currency;
        this.reportedAt = reportedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getPayerAccount() {
        return payerAccount;
    }

    public UUID getPayeeAccount() {
        return payeeAccount;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }
}
