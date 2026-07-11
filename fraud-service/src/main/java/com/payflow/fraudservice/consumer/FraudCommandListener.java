package com.payflow.fraudservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.commands.CheckFraudCommand;
import com.payflow.common.events.EventEnvelope;
import com.payflow.common.events.FraudApprovedEvent;
import com.payflow.common.events.FraudRejectedEvent;
import com.payflow.common.events.PaymentEventType;
import com.payflow.fraudservice.domain.ProcessedEvent;
import com.payflow.fraudservice.repository.ProcessedEventRepository;
import com.payflow.fraudservice.rules.FraudRuleEngine;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class FraudCommandListener {

    private static final Logger log = LoggerFactory.getLogger(FraudCommandListener.class);
    private static final String EVENTS_TOPIC = "payment.events";

    private final ProcessedEventRepository processedEventRepository;
    private final FraudRuleEngine ruleEngine;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public FraudCommandListener(ProcessedEventRepository processedEventRepository,
                                 FraudRuleEngine ruleEngine,
                                 KafkaTemplate<String, String> kafkaTemplate,
                                 ObjectMapper objectMapper) {
        this.processedEventRepository = processedEventRepository;
        this.ruleEngine = ruleEngine;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment.commands", containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onCommand(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);

            if (!"CHECK_FRAUD".equals(envelope.eventType())) {
                ack.acknowledge();
                return;
            }
            if (processedEventRepository.existsById(envelope.eventId())) {
                log.debug("Skipping already-processed command {}", envelope.eventId());
                ack.acknowledge();
                return;
            }

            CheckFraudCommand command = objectMapper.treeToValue(envelope.payload(), CheckFraudCommand.class);
            FraudRuleEngine.Verdict verdict = ruleEngine.evaluate(command);

            if (verdict.approved()) {
                publish(command.paymentId(), PaymentEventType.FRAUD_APPROVED,
                        new FraudApprovedEvent(command.paymentId(), Instant.now()));
                log.info("Payment {} fraud check APPROVED", command.paymentId());
            } else {
                publish(command.paymentId(), PaymentEventType.FRAUD_REJECTED,
                        new FraudRejectedEvent(command.paymentId(), verdict.reason(), Instant.now()));
                log.info("Payment {} fraud check REJECTED: {}", command.paymentId(), verdict.reason());
            }

            processedEventRepository.save(new ProcessedEvent(envelope.eventId(), Instant.now()));
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process fraud command, will redeliver", e);
        }
    }

    private void publish(UUID paymentId, PaymentEventType type, Object payload) throws Exception {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                paymentId,
                type.name(),
                Instant.now(),
                objectMapper.valueToTree(payload)
        );
        kafkaTemplate.send(EVENTS_TOPIC, paymentId.toString(), objectMapper.writeValueAsString(envelope)).get();
    }
}
