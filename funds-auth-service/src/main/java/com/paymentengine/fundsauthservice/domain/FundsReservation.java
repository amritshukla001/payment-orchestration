package com.payflow.fundsauthservice.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per payment that successfully reserved funds. Its status is the
 * guard that makes a duplicate or out-of-order RELEASE_FUNDS a safe no-op.
 */
@Entity
@Table(name = "funds_reservations")
public class FundsReservation {

    @Id
    private UUID paymentId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FundsReservation() {
        // JPA
    }

    public FundsReservation(UUID paymentId, UUID accountId, long amountCents, Instant now) {
        this.paymentId = paymentId;
        this.accountId = accountId;
        this.amountCents = amountCents;
        this.status = ReservationStatus.RESERVED;
        this.createdAt = now;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void markReleased() {
        this.status = ReservationStatus.RELEASED;
    }
}
