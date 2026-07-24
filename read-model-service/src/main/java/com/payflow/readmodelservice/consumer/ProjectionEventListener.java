package com.payflow.readmodelservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.enums.PaymentState;
import com.payflow.common.events.EventEnvelope;
import com.payflow.common.events.LedgerFinalizedEvent;
import com.payflow.common.events.LedgerPostedEvent;
import com.payflow.common.events.LedgerReversedEvent;
import com.payflow.common.events.NotificationSentEvent;
import com.payflow.common.events.PaymentInitiatedEvent;
import com.payflow.readmodelservice.domain.LedgerEntryView;
import com.payflow.readmodelservice.domain.NotificationView;
import com.payflow.readmodelservice.domain.PaymentView;
import com.payflow.readmodelservice.domain.ProcessedEvent;
import com.payflow.readmodelservice.repository.LedgerEntryViewRepository;
import com.payflow.readmodelservice.repository.NotificationViewRepository;
import com.payflow.readmodelservice.repository.PaymentViewRepository;
import com.payflow.readmodelservice.repository.ProcessedEventRepository;
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
 * Builds the CQRS read model by projecting payment.events into
 * payment_view/ledger_entry_view/notification_view. Unlike the write-side
 * services, nothing here issues commands or reacts to failures beyond
 * logging -- this is a pure Observer over the event stream, asynchronously
 * consistent with (never a source of truth for) the write-side stores.
 */
@Component
public class ProjectionEventListener {

    private static final Logger log = LoggerFactory.getLogger(ProjectionEventListener.class);

    private final PaymentViewRepository paymentViewRepository;
    private final LedgerEntryViewRepository ledgerEntryViewRepository;
    private final NotificationViewRepository notificationViewRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public ProjectionEventListener(PaymentViewRepository paymentViewRepository,
                                    LedgerEntryViewRepository ledgerEntryViewRepository,
                                    NotificationViewRepository notificationViewRepository,
                                    ProcessedEventRepository processedEventRepository,
                                    ObjectMapper objectMapper) {
        this.paymentViewRepository = paymentViewRepository;
        this.ledgerEntryViewRepository = ledgerEntryViewRepository;
        this.notificationViewRepository = notificationViewRepository;
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
            case "PAYMENT_INITIATED" -> onPaymentInitiated(envelope);
            case "FRAUD_APPROVED" -> advanceState(envelope, PaymentState.FRAUD_CHECKED);
            case "FRAUD_REJECTED" -> advanceState(envelope, PaymentState.FAILED);
            case "FUNDS_AUTHORIZED" -> advanceState(envelope, PaymentState.AUTHORIZED);
            case "FUNDS_AUTHORIZATION_FAILED" -> advanceState(envelope, PaymentState.FAILED);
            case "LEDGER_POSTED" -> onLedgerPosted(envelope);
            case "PAYMENT_SETTLED" -> advanceState(envelope, PaymentState.SETTLED);
            case "LEDGER_FINALIZED" -> onLedgerFinalized(envelope);
            case "PAYMENT_FAILED" -> advanceState(envelope, PaymentState.FAILED);
            case "SETTLEMENT_DECLINED" -> advanceState(envelope, PaymentState.COMPENSATING);
            case "LEDGER_REVERSED" -> onLedgerReversed(envelope);
            case "PAYMENT_COMPENSATED" -> advanceState(envelope, PaymentState.COMPENSATED);
            case "NOTIFICATION_SENT" -> onNotificationSent(envelope);
            default -> { /* FUNDS_RELEASED and anything else: informational only */ }
        }

        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), Instant.now()));
        ack.acknowledge();
    }

    private void onEventProcessingFailed(ConsumerRecord<String, String> record, Acknowledgment ack, Throwable t) {
        log.error("Failed to process payment event after retries exhausted, will redeliver", t);
    }

    private void onPaymentInitiated(EventEnvelope envelope) throws Exception {
        PaymentInitiatedEvent event = objectMapper.treeToValue(envelope.payload(), PaymentInitiatedEvent.class);
        paymentViewRepository.save(new PaymentView(
                event.paymentId(), event.payerAccount(), event.payeeAccount(),
                event.amountCents(), event.currency(), PaymentState.INITIATED, event.occurredAt()));
    }

    private void advanceState(EventEnvelope envelope, PaymentState state) {
        UUID paymentId = envelope.aggregateId();
        paymentViewRepository.findById(paymentId).ifPresentOrElse(
                view -> {
                    view.advanceTo(state, Instant.now());
                    paymentViewRepository.save(view);
                },
                () -> log.warn("Received {} for unknown payment {}", envelope.eventType(), paymentId));
    }

    private void onLedgerPosted(EventEnvelope envelope) throws Exception {
        LedgerPostedEvent event = objectMapper.treeToValue(envelope.payload(), LedgerPostedEvent.class);
        saveLedgerEntry(event.id(), event.paymentId(), event.debitAccount(), event.creditAccount(),
                event.amountCents(), event.postingType(), event.occurredAt());
        advanceState(envelope, PaymentState.LEDGER_POSTED);
    }

    private void onLedgerFinalized(EventEnvelope envelope) throws Exception {
        LedgerFinalizedEvent event = objectMapper.treeToValue(envelope.payload(), LedgerFinalizedEvent.class);
        saveLedgerEntry(event.id(), event.paymentId(), event.debitAccount(), event.creditAccount(),
                event.amountCents(), event.postingType(), event.occurredAt());
    }

    private void onLedgerReversed(EventEnvelope envelope) throws Exception {
        LedgerReversedEvent event = objectMapper.treeToValue(envelope.payload(), LedgerReversedEvent.class);
        saveLedgerEntry(event.id(), event.paymentId(), event.debitAccount(), event.creditAccount(),
                event.amountCents(), event.postingType(), event.occurredAt());
    }

    private void saveLedgerEntry(UUID id, UUID paymentId, UUID debitAccount, UUID creditAccount,
                                  long amountCents, String postingType, Instant postedAt) {
        ledgerEntryViewRepository.save(new LedgerEntryView(
                id, paymentId, debitAccount, creditAccount, amountCents, postingType, postedAt));
    }

    private void onNotificationSent(EventEnvelope envelope) throws Exception {
        NotificationSentEvent event = objectMapper.treeToValue(envelope.payload(), NotificationSentEvent.class);
        notificationViewRepository.save(new NotificationView(
                event.id(), event.paymentId(), event.accountId(),
                event.recipient(), event.outcome(), event.message(), event.sentAt()));
    }
}
