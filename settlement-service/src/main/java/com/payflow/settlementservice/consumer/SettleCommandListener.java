package com.payflow.settlementservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.commands.SettleCommand;
import com.payflow.common.events.EventEnvelope;
import com.payflow.common.events.PaymentEventType;
import com.payflow.common.events.PaymentSettledEvent;
import com.payflow.common.events.SettlementDeclinedEvent;
import com.payflow.settlementservice.domain.ProcessedEvent;
import com.payflow.settlementservice.domain.Settlement;
import com.payflow.settlementservice.repository.ProcessedEventRepository;
import com.payflow.settlementservice.repository.SettlementRepository;
import com.payflow.settlementservice.risk.SettlementRiskCheck;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class SettleCommandListener {

    private static final Logger log = LoggerFactory.getLogger(SettleCommandListener.class);
    private static final String EVENTS_TOPIC = "payment.events";

    private final SettlementRepository settlementRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final SettlementRiskCheck riskCheck;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public SettleCommandListener(SettlementRepository settlementRepository,
                                  ProcessedEventRepository processedEventRepository,
                                  SettlementRiskCheck riskCheck,
                                  KafkaTemplate<String, String> kafkaTemplate,
                                  ObjectMapper objectMapper) {
        this.settlementRepository = settlementRepository;
        this.processedEventRepository = processedEventRepository;
        this.riskCheck = riskCheck;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment.commands", containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onCommand(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);

            if (!"SETTLE".equals(envelope.eventType())) {
                ack.acknowledge();
                return;
            }
            if (processedEventRepository.existsById(envelope.eventId())) {
                log.debug("Skipping already-processed command {}", envelope.eventId());
                ack.acknowledge();
                return;
            }

            SettleCommand command = objectMapper.treeToValue(envelope.payload(), SettleCommand.class);
            Optional<String> decline = riskCheck.checkForDecline(command.amountCents());

            if (decline.isPresent()) {
                publish(command.paymentId(), PaymentEventType.SETTLEMENT_DECLINED,
                        new SettlementDeclinedEvent(command.paymentId(), decline.get(), Instant.now()));
                log.info("Payment {} SETTLEMENT_DECLINED: {}", command.paymentId(), decline.get());
            } else {
                recordCapture(command);
                publish(command.paymentId(), PaymentEventType.PAYMENT_SETTLED, new PaymentSettledEvent(
                        command.paymentId(), command.payerAccount(), command.payeeAccount(), Instant.now()));
                log.info("Payment {} SETTLED (captured)", command.paymentId());
            }

            processedEventRepository.save(new ProcessedEvent(envelope.eventId(), Instant.now()));
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process settle command, will redeliver", e);
        }
    }

    private void recordCapture(SettleCommand command) {
        if (settlementRepository.existsById(command.paymentId())) {
            return; // already captured — safe no-op on redelivery
        }
        settlementRepository.save(new Settlement(command.paymentId(), command.amountCents(), Instant.now()));
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
