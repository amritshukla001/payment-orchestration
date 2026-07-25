package com.payflow.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per event this service has already acted on. Checked before
 * processing any message so Kafka's at-least-once redelivery can never
 * turn into double-processing.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // JPA
    }

    public ProcessedEvent(UUID eventId, Instant processedAt) {
        this.eventId = eventId;
        this.processedAt = processedAt;
    }

    public UUID getEventId() {
        return eventId;
    }
}
