package com.payflow.ledgerservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.commands.PostLedgerCommand;
import com.payflow.common.events.EventEnvelope;
import com.payflow.common.events.LedgerPostedEvent;
import com.payflow.common.events.PaymentEventType;
import com.payflow.ledgerservice.domain.ProcessedEvent;
import com.payflow.ledgerservice.ledger.DoubleEntryLedger;
import com.payflow.ledgerservice.repository.ProcessedEventRepository;
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
public class PostLedgerCommandListener {

    private static final Logger log = LoggerFactory.getLogger(PostLedgerCommandListener.class);
    private static final String EVENTS_TOPIC = "payment.events";

    private final ProcessedEventRepository processedEventRepository;
    private final DoubleEntryLedger ledger;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public PostLedgerCommandListener(ProcessedEventRepository processedEventRepository,
                                      DoubleEntryLedger ledger,
                                      KafkaTemplate<String, String> kafkaTemplate,
                                      ObjectMapper objectMapper) {
        this.processedEventRepository = processedEventRepository;
        this.ledger = ledger;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment.commands", containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onCommand(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);

            if (!"POST_LEDGER".equals(envelope.eventType())) {
                ack.acknowledge();
                return;
            }
            if (processedEventRepository.existsById(envelope.eventId())) {
                log.debug("Skipping already-processed command {}", envelope.eventId());
                ack.acknowledge();
                return;
            }

            PostLedgerCommand command = objectMapper.treeToValue(envelope.payload(), PostLedgerCommand.class);
            ledger.postHold(command.paymentId(), command.payerAccount(), command.amountCents());

            publish(command.paymentId(), new LedgerPostedEvent(command.paymentId(), Instant.now()));
            log.info("Payment {} ledger HOLD posted", command.paymentId());

            processedEventRepository.save(new ProcessedEvent(envelope.eventId(), Instant.now()));
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process ledger command, will redeliver", e);
        }
    }

    private void publish(UUID paymentId, Object payload) throws Exception {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                paymentId,
                PaymentEventType.LEDGER_POSTED.name(),
                Instant.now(),
                objectMapper.valueToTree(payload)
        );
        kafkaTemplate.send(EVENTS_TOPIC, paymentId.toString(), objectMapper.writeValueAsString(envelope)).get();
    }
}
