package com.payflow.paymentengine.domain;

import com.payflow.common.enums.PaymentState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The payment's current-state projection, folded from its PaymentEngineEvent log --
 * never itself persisted. This is a separate copy from payment-api's
 * Payment row by design: each service owns its own view, kept in sync
 * only via events on the bus.
 */
public final class PaymentEngineAggregate {

    private final UUID paymentId;
    private final UUID payerAccount;
    private final UUID payeeAccount;
    private final long amountCents;
    private final String currency;
    private final String paymentMethod;
    private PaymentState state;
    private Instant updatedAt;
    private int eventCount;

    public PaymentEngineAggregate(UUID paymentId, UUID payerAccount, UUID payeeAccount, long amountCents,
                                 String currency, String paymentMethod, PaymentState state, Instant updatedAt) {
        this.paymentId = paymentId;
        this.payerAccount = payerAccount;
        this.payeeAccount = payeeAccount;
        this.amountCents = amountCents;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.state = state;
        this.updatedAt = updatedAt;
        this.eventCount = 1;
    }

    /**
     * Reconstructs the current state by folding over the full transition
     * log, ascending by sequence number -- the first row (PAYMENT_INITIATED)
     * carries payer/payee/amount/currency/paymentMethod, every later row
     * only changes state/updatedAt.
     */
    public static PaymentEngineAggregate replay(List<PaymentEngineEvent> events) {
        PaymentEngineEvent first = events.get(0);
        PaymentEngineAggregate aggregate = new PaymentEngineAggregate(
                first.getPaymentId(), first.getPayerAccount(), first.getPayeeAccount(),
                first.getAmountCents(), first.getCurrency(), first.getPaymentMethod(),
                PaymentState.valueOf(first.getToState()), first.getOccurredAt());

        for (int i = 1; i < events.size(); i++) {
            PaymentEngineEvent event = events.get(i);
            aggregate.advanceTo(PaymentState.valueOf(event.getToState()), event.getOccurredAt());
        }
        return aggregate;
    }

    private void advanceTo(PaymentState state, Instant updatedAt) {
        this.state = state;
        this.updatedAt = updatedAt;
        this.eventCount++;
    }

    /** The sequence number the next appended PaymentEngineEvent for this payment should use. */
    public int nextSequenceNumber() {
        return eventCount;
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
