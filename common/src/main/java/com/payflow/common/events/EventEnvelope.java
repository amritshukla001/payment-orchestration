package com.payflow.common.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Wraps every message published to payment.events / payment.commands.
 *
 * eventId is the stable identity every consumer dedupes against (their
 * processed_events table keys on this) — without it, at-least-once
 * delivery from Kafka has no way to distinguish a redelivery from a new event.
 */
public record EventEnvelope(
        UUID eventId,
        UUID aggregateId,
        String eventType,
        Instant occurredAt,
        JsonNode payload
) {
}
