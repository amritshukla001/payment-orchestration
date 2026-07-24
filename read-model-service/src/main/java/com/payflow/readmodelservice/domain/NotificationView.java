package com.payflow.readmodelservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A denormalized copy of one notification, projected from a
 * NOTIFICATION_SENT event -- not read from notification-service's own
 * notifications table.
 */
@Entity
@Table(name = "notification_view")
public class NotificationView {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false, length = 10)
    private String recipient;

    @Column(nullable = false, length = 10)
    private String outcome;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected NotificationView() {
        // JPA
    }

    public NotificationView(UUID id, UUID paymentId, UUID accountId, String recipient,
                             String outcome, String message, Instant sentAt) {
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

    public String getRecipient() {
        return recipient;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getMessage() {
        return message;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
