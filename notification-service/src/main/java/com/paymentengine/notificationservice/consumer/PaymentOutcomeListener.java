package com.paymentengine.notificationservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentengine.common.events.EventEnvelope;
import com.paymentengine.common.events.NotificationSentEvent;
import com.paymentengine.common.events.PaymentCompensatedEvent;
import com.paymentengine.common.events.PaymentEventType;
import com.paymentengine.common.events.PaymentFailedEvent;
import com.paymentengine.common.events.PaymentSettledEvent;
import com.paymentengine.notificationservice.domain.NotificationRecord;
import com.paymentengine.notificationservice.domain.Outcome;
import com.paymentengine.notificationservice.domain.ProcessedEvent;
import com.paymentengine.notificationservice.domain.Recipient;
import com.paymentengine.notificationservice.repository.NotificationRecordRepository;
import com.paymentengine.notificationservice.repository.ProcessedEventRepository;
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

/**
 * Unlike fraud-service/funds-auth-service/ledger-service/settlement-service
 * -- each of which reacts to a command the orchestrator explicitly issued
 * -- this listener subscribes directly to payment.events as a passive
 * observer. Nothing depends on a notification succeeding, so there's no
 * command/response round trip with the orchestrator: this is the Observer
 * pattern applied for real, where the others are closer to Command.
 */
@Component
public class PaymentOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomeListener.class);
    private static final String EVENTS_TOPIC = "payment.events";

    private final NotificationRecordRepository notificationRecordRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public PaymentOutcomeListener(NotificationRecordRepository notificationRecordRepository,
                                   ProcessedEventRepository processedEventRepository,
                                   KafkaTemplate<String, String> kafkaTemplate,
                                   ObjectMapper objectMapper) {
        this.notificationRecordRepository = notificationRecordRepository;
        this.processedEventRepository = processedEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment.events", containerFactory = "kafkaListenerContainerFactory")
    @Transactional(rollbackFor = Exception.class)
    @Retry(name = "kafka-consumer", fallbackMethod = "onEventProcessingFailed")
    public void onEvent(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);

        if (processedEventRepository.existsById(envelope.eventId())) {
            log.debug("Skipping already-processed event {}", envelope.eventId());
            ack.acknowledge();
            return;
        }

        switch (envelope.eventType()) {
            case "PAYMENT_SETTLED" -> onPaymentSettled(envelope);
            case "PAYMENT_FAILED" -> onPaymentFailed(envelope);
            case "PAYMENT_COMPENSATED" -> onPaymentCompensated(envelope);
            default -> { /* not a terminal outcome we notify on */ }
        }

        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), Instant.now()));
        ack.acknowledge();
    }

    private void onEventProcessingFailed(ConsumerRecord<String, String> record, Acknowledgment ack, Throwable t) {
        log.error("Failed to process payment event after retries exhausted, will redeliver", t);
    }

    private void onPaymentSettled(EventEnvelope envelope) throws Exception {
        PaymentSettledEvent event = objectMapper.treeToValue(envelope.payload(), PaymentSettledEvent.class);
        notify(event.paymentId(), event.payerAccount(), Recipient.PAYER, Outcome.SUCCESS,
                "Your payment " + event.paymentId() + " has settled.");
        notify(event.paymentId(), event.payeeAccount(), Recipient.PAYEE, Outcome.SUCCESS,
                "You've received payment " + event.paymentId() + ".");
        log.info("Payment {} notifications sent to payer and payee (SETTLED)", event.paymentId());
    }

    private void onPaymentFailed(EventEnvelope envelope) throws Exception {
        PaymentFailedEvent event = objectMapper.treeToValue(envelope.payload(), PaymentFailedEvent.class);
        notify(event.paymentId(), event.payerAccount(), Recipient.PAYER, Outcome.FAILURE,
                "Your payment " + event.paymentId() + " failed: " + event.reason());
        log.info("Payment {} notification sent to payer only (FAILED: {})", event.paymentId(), event.reason());
    }

    private void onPaymentCompensated(EventEnvelope envelope) throws Exception {
        PaymentCompensatedEvent event = objectMapper.treeToValue(envelope.payload(), PaymentCompensatedEvent.class);
        notify(event.paymentId(), event.payerAccount(), Recipient.PAYER, Outcome.REVERSED,
                "Your payment " + event.paymentId() + " could not be completed and has been reversed; your funds have been returned.");
        log.info("Payment {} notification sent to payer only (COMPENSATED)", event.paymentId());
    }

    private void notify(UUID paymentId, UUID accountId, Recipient recipient, Outcome outcome, String message) throws Exception {
        NotificationRecord record = notificationRecordRepository.save(new NotificationRecord(
                UUID.randomUUID(), paymentId, accountId, recipient, outcome, message, Instant.now()));
        publishSent(record);
    }

    private void publishSent(NotificationRecord record) throws Exception {
        NotificationSentEvent event = new NotificationSentEvent(
                record.getId(), record.getPaymentId(), record.getAccountId(),
                record.getRecipient().name(), record.getOutcome().name(),
                record.getMessage(), record.getSentAt());
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), record.getPaymentId(), PaymentEventType.NOTIFICATION_SENT.name(),
                Instant.now(), objectMapper.valueToTree(event));
        kafkaTemplate.send(EVENTS_TOPIC, record.getPaymentId().toString(), objectMapper.writeValueAsString(envelope)).get();
    }
}
