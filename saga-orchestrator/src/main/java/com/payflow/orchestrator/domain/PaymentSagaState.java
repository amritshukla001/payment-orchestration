package com.payflow.orchestrator.domain;

import com.payflow.common.enums.PaymentState;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * The orchestrator's own projection of a payment's progress through the saga.
 * This is a separate copy from payment-api's Payment row by design — each
 * service owns its own view, kept in sync only via events on the bus.
 */
@Entity
@Table(name = "payment_saga_state")
public class PaymentSagaState {

    @Id
    private UUID paymentId;

    @Column(name = "payer_account", nullable = false)
    private UUID payerAccount;

    @Column(name = "payee_account", nullable = false)
    private UUID payeeAccount;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentState state;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentSagaState() {
        // JPA
    }

    public PaymentSagaState(UUID paymentId, UUID payerAccount, UUID payeeAccount, long amountCents, String currency,
                             PaymentState state, Instant now) {
        this.paymentId = paymentId;
        this.payerAccount = payerAccount;
        this.payeeAccount = payeeAccount;
        this.amountCents = amountCents;
        this.currency = currency;
        this.state = state;
        this.updatedAt = now;
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

    public PaymentState getState() {
        return state;
    }

    public void advanceTo(PaymentState state, Instant now) {
        this.state = state;
        this.updatedAt = now;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
