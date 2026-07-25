package com.paymentengine.readmodelservice.domain;

import com.paymentengine.common.enums.PaymentState;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * The read model's own projection of a payment -- a denormalized copy kept
 * in sync only via payment.events, never queried from or written back to
 * any write-side service's table.
 */
@Entity
@Table(name = "payment_view")
public class PaymentView {

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

    @Column(name = "payment_method", nullable = false, length = 16)
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentState state;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentView() {
        // JPA
    }

    public PaymentView(UUID paymentId, UUID payerAccount, UUID payeeAccount, long amountCents,
                        String currency, String paymentMethod, PaymentState state, Instant updatedAt) {
        this.paymentId = paymentId;
        this.payerAccount = payerAccount;
        this.payeeAccount = payeeAccount;
        this.amountCents = amountCents;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.state = state;
        this.updatedAt = updatedAt;
    }

    public void advanceTo(PaymentState state, Instant updatedAt) {
        this.state = state;
        this.updatedAt = updatedAt;
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

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public PaymentState getState() {
        return state;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
