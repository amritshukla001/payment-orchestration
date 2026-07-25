package com.paymentengine.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A generated compensation summary, cached so the LLM call happens at most
 * once per payment. The saga's own facts live in payment_saga_events; this
 * is derived prose about them, safe to delete and regenerate at any time.
 */
@Entity
@Table(name = "payment_saga_summaries")
public class SagaSummary {

    public enum Source { AI, DETERMINISTIC }

    @Id
    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(nullable = false, columnDefinition = "text")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    protected SagaSummary() {
        // JPA
    }

    public SagaSummary(UUID paymentId, String summary, Source source, Instant generatedAt) {
        this.paymentId = paymentId;
        this.summary = summary;
        this.source = source;
        this.generatedAt = generatedAt;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getSummary() {
        return summary;
    }

    public Source getSource() {
        return source;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }
}
