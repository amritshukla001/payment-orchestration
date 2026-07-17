package com.payflow.ledgerservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.commands.PostFinalLedgerCommand;
import com.payflow.common.commands.PostLedgerCommand;
import com.payflow.common.commands.ReverseLedgerCommand;
import com.payflow.common.events.EventEnvelope;
import com.payflow.common.events.LedgerFinalizedEvent;
import com.payflow.common.events.LedgerPostedEvent;
import com.payflow.common.events.LedgerReversedEvent;
import com.payflow.common.events.PaymentEventType;
import com.payflow.ledgerservice.domain.ProcessedEvent;
import com.payflow.ledgerservice.ledger.DoubleEntryLedger;
import com.payflow.ledgerservice.repository.ProcessedEventRepository;
import io.github.resilience4j.retry.annotation.Retry;
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
public class LedgerCommandListener {

    private static final Logger log = LoggerFactory.getLogger(LedgerCommandListener.class);
    private static final String EVENTS_TOPIC = "payment.events";

    private final ProcessedEventRepository processedEventRepository;
    private final DoubleEntryLedger ledger;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public LedgerCommandListener(ProcessedEventRepository processedEventRepository,
                                  DoubleEntryLedger ledger,
                                  KafkaTemplate<String, String> kafkaTemplate,
                                  ObjectMapper objectMapper) {
        this.processedEventRepository = processedEventRepository;
        this.ledger = ledger;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment.commands", containerFactory = "kafkaListenerContainerFactory")
    @Transactional(rollbackFor = Exception.class)
    @Retry(name = "kafka-consumer", fallbackMethod = "onCommandProcessingFailed")
    public void onCommand(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);

        if (processedEventRepository.existsById(envelope.eventId())) {
            log.debug("Skipping already-processed command {}", envelope.eventId());
            ack.acknowledge();
            return;
        }

        switch (envelope.eventType()) {
            case "POST_LEDGER" -> handlePostLedger(envelope);
            case "POST_FINAL_LEDGER" -> handlePostFinalLedger(envelope);
            case "REVERSE_LEDGER" -> handleReverseLedger(envelope);
            default -> { /* not for us */ }
        }

        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), Instant.now()));
        ack.acknowledge();
    }

    private void onCommandProcessingFailed(ConsumerRecord<String, String> record, Acknowledgment ack, Throwable t) {
        log.error("Failed to process ledger command after retries exhausted, will redeliver", t);
    }

    private void handlePostLedger(EventEnvelope envelope) throws Exception {
        PostLedgerCommand command = objectMapper.treeToValue(envelope.payload(), PostLedgerCommand.class);
        ledger.postHold(command.paymentId(), command.payerAccount(), command.amountCents());
        publish(command.paymentId(), PaymentEventType.LEDGER_POSTED, new LedgerPostedEvent(command.paymentId(), Instant.now()));
        log.info("Payment {} ledger HOLD posted", command.paymentId());
    }

    private void handlePostFinalLedger(EventEnvelope envelope) throws Exception {
        PostFinalLedgerCommand command = objectMapper.treeToValue(envelope.payload(), PostFinalLedgerCommand.class);
        ledger.postFinal(command.paymentId(), command.payeeAccount(), command.amountCents());
        publish(command.paymentId(), PaymentEventType.LEDGER_FINALIZED, new LedgerFinalizedEvent(command.paymentId(), Instant.now()));
        log.info("Payment {} ledger FINAL posted", command.paymentId());
    }

    private void handleReverseLedger(EventEnvelope envelope) throws Exception {
        ReverseLedgerCommand command = objectMapper.treeToValue(envelope.payload(), ReverseLedgerCommand.class);
        ledger.reverseHold(command.paymentId(), command.payerAccount(), command.amountCents());
        publish(command.paymentId(), PaymentEventType.LEDGER_REVERSED, new LedgerReversedEvent(command.paymentId(), Instant.now()));
        log.info("Payment {} ledger HOLD reversed (compensation)", command.paymentId());
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
