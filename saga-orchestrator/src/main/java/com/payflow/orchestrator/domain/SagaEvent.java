package com.payflow.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One immutable fact per saga transition -- never updated or deleted, the
 * same append-only discipline as ledger-service's LedgerEntry. This is the
 * saga's actual system of record; PaymentSagaAggregate is a disposable
 * projection folded from these rows on read, not persisted itself.
 * payerAccount/payeeAccount/amountCents/currency are only ever populated on
 * the sequenceNumber = 0 (PAYMENT_INITIATED) row, since those fields never
 * change afterward -- every later row leaves them null.
 */
@Entity
@Table(name = "payment_saga_events")
public class SagaEvent {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "to_state", nullable = false, length = 20)
    private String toState;

    @Column(name = "payer_account")
    private UUID payerAccount;

    @Column(name = "payee_account")
    private UUID payeeAccount;

    @Column(name = "amount_cents")
    private Long amountCents;

    @Column(length = 3)
    private String currency;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected SagaEvent() {
        // JPA
    }

    public SagaEvent(UUID id, UUID paymentId, int sequenceNumber, String eventType, String toState,
                      UUID payerAccount, UUID payeeAccount, Long amountCents, String currency, Instant occurredAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.sequenceNumber = sequenceNumber;
        this.eventType = eventType;
        this.toState = toState;
        this.payerAccount = payerAccount;
        this.payeeAccount = payeeAccount;
        this.amountCents = amountCents;
        this.currency = currency;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public String getEventType() {
        return eventType;
    }

    public String getToState() {
        return toState;
    }

    public UUID getPayerAccount() {
        return payerAccount;
    }

    public UUID getPayeeAccount() {
        return payeeAccount;
    }

    public Long getAmountCents() {
        return amountCents;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
