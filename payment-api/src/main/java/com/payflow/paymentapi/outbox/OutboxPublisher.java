package com.payflow.paymentapi.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.events.EventEnvelope;
import com.payflow.paymentapi.domain.OutboxEvent;
import com.payflow.paymentapi.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Polls the outbox table and forwards unpublished rows to Kafka.
 *
 * This is at-least-once by design: if the process crashes after the Kafka
 * send succeeds but before the row is marked published, the row is resent
 * on the next poll. Consumers must be idempotent (see the design doc) —
 * that's the trade this pattern makes in exchange for never losing an event.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final String TOPIC = "payment.events";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                            KafkaTemplate<String, String> kafkaTemplate,
                            ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 1000)
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findTop50ByPublishedFalseOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            publishOne(event);
        }
    }

    @Transactional
    void publishOne(OutboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            EventEnvelope envelope = new EventEnvelope(
                    event.getId(),
                    event.getAggregateId(),
                    event.getEventType(),
                    event.getCreatedAt(),
                    payload
            );
            String envelopeJson = objectMapper.writeValueAsString(envelope);

            // Keyed by aggregate id so all events for one payment land on the
            // same partition and are consumed in order.
            kafkaTemplate.send(TOPIC, event.getAggregateId().toString(), envelopeJson).get();
            event.markPublished();
            outboxEventRepository.save(event);
        } catch (Exception e) {
            log.warn("Failed to publish outbox event {} ({}), will retry on next poll",
                    event.getId(), event.getEventType(), e);
        }
    }
}
