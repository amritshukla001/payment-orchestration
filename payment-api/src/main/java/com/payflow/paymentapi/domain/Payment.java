package com.payflow.paymentapi.domain;

import com.payflow.common.enums.PaymentMethod;
import com.payflow.common.enums.PaymentState;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "payer_account", nullable = false)
    private UUID payerAccount;

    @Column(name = "payee_account", nullable = false)
    private UUID payeeAccount;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 16)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentState state;

    // Populated only for CARD payments when Stripe verification is
    // enabled (StripeCardTokenVerifier) -- an audit trail only, never
    // read by the saga.
    @Column(name = "stripe_payment_method_id")
    private String stripePaymentMethodId;

    // Optimistic lock: guards against two orchestrator instances advancing
    // the same payment's state concurrently.
    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
        // JPA
    }

    public Payment(UUID id, String idempotencyKey, UUID payerAccount, UUID payeeAccount,
                   long amountCents, String currency, PaymentMethod paymentMethod, PaymentState state, Instant now,
                   String stripePaymentMethodId) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.payerAccount = payerAccount;
        this.payeeAccount = payeeAccount;
        this.amountCents = amountCents;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.state = state;
        this.createdAt = now;
        this.updatedAt = now;
        this.stripePaymentMethodId = stripePaymentMethodId;
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
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

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public PaymentState getState() {
        return state;
    }

    public String getStripePaymentMethodId() {
        return stripePaymentMethodId;
    }

    public void setState(PaymentState state, Instant now) {
        this.state = state;
        this.updatedAt = now;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
