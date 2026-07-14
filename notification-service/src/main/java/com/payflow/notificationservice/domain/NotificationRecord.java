package com.payflow.notificationservice.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A mock notification — this is a portfolio project, so "sending" means
 * logging and persisting a record, not actually delivering an email/SMS.
 * The point is the pipeline (terminal outcome in, notification recorded
 * out), not a real delivery integration.
 */
@Entity
@Table(name = "notifications")
public class NotificationRecord {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Recipient recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Outcome outcome;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected NotificationRecord() {
        // JPA
    }

    public NotificationRecord(UUID id, UUID paymentId, UUID accountId, Recipient recipient,
                               Outcome outcome, String message, Instant sentAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.accountId = accountId;
        this.recipient = recipient;
        this.outcome = outcome;
        this.message = message;
        this.sentAt = sentAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public Recipient getRecipient() {
        return recipient;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getMessage() {
        return message;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
