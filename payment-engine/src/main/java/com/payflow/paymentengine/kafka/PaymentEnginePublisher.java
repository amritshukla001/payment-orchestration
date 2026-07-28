package com.payflow.paymentengine.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.events.EventEnvelope;
import com.payflow.common.events.PaymentEventType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * The one place that writes onto payment.commands/payment.events -- used
 * by the Kafka-driven PaymentEventListener and also by REST/scheduled
 * call sites (StepUpController, StepUpTimeoutScheduler) that need to push
 * the saga forward without an incoming EventEnvelope to react to.
 */
@Component
public class PaymentEnginePublisher {

    private static final String COMMANDS_TOPIC = "payment.commands";
    private static final String EVENTS_TOPIC = "payment.events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public PaymentEnginePublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishCommand(UUID paymentId, String commandType, Object payload) throws Exception {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), paymentId, commandType, Instant.now(), objectMapper.valueToTree(payload));
        kafkaTemplate.send(COMMANDS_TOPIC, paymentId.toString(), objectMapper.writeValueAsString(envelope)).get();
    }

    public void publishEvent(UUID paymentId, PaymentEventType type, Object payload) throws Exception {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), paymentId, type.name(), Instant.now(), objectMapper.valueToTree(payload));
        kafkaTemplate.send(EVENTS_TOPIC, paymentId.toString(), objectMapper.writeValueAsString(envelope)).get();
    }
}
