package com.payflow.notificationservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.events.EventEnvelope;
import com.payflow.common.events.PaymentCompensatedEvent;
import com.payflow.common.events.PaymentFailedEvent;
import com.payflow.common.events.PaymentSettledEvent;
import com.payflow.notificationservice.domain.NotificationRecord;
import com.payflow.notificationservice.domain.Outcome;
import com.payflow.notificationservice.domain.ProcessedEvent;
import com.payflow.notificationservice.domain.Recipient;
import com.payflow.notificationservice.repository.NotificationRecordRepository;
import com.payflow.notificationservice.repository.ProcessedEventRepository;
import io.github.resilience4j.retry.annotation.Retry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
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

    private final NotificationRecordRepository notificationRecordRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public PaymentOutcomeListener(NotificationRecordRepository notificationRecordRepository,
                                   ProcessedEventRepository processedEventRepository,
                                   ObjectMapper objectMapper) {
        this.notificationRecordRepository = notificationRecordRepository;
        this.processedEventRepository = processedEventRepository;
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

    private void notify(UUID paymentId, UUID accountId, Recipient recipient, Outcome outcome, String message) {
        notificationRecordRepository.save(new NotificationRecord(
                UUID.randomUUID(), paymentId, accountId, recipient, outcome, message, Instant.now()));
    }
}
