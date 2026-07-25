package com.payflow.paymentengine.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.commands.AuthorizeFundsCommand;
import com.payflow.common.commands.CheckComplianceCommand;
import com.payflow.common.commands.CheckFraudCommand;
import com.payflow.common.commands.PostFinalLedgerCommand;
import com.payflow.common.commands.PostLedgerCommand;
import com.payflow.common.commands.ReleaseFundsCommand;
import com.payflow.common.commands.ReverseLedgerCommand;
import com.payflow.common.commands.SettleCommand;
import com.payflow.common.enums.PaymentState;
import com.payflow.common.events.ComplianceRejectedEvent;
import com.payflow.common.events.EventEnvelope;
import com.payflow.common.events.FraudRejectedEvent;
import com.payflow.common.events.FundsAuthorizationFailedEvent;
import com.payflow.common.events.PaymentCompensatedEvent;
import com.payflow.common.events.PaymentEventType;
import com.payflow.common.events.PaymentFailedEvent;
import com.payflow.common.events.PaymentInitiatedEvent;
import com.payflow.common.events.SettlementDeclinedEvent;
import com.payflow.paymentengine.domain.PaymentEngineAggregate;
import com.payflow.paymentengine.domain.ProcessedEvent;
import com.payflow.paymentengine.domain.PaymentEngineEventStore;
import com.payflow.paymentengine.repository.ProcessedEventRepository;
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
 * Consumes the domain-event stream and drives the payment engine's state machine.
 * On each new fact, decides whether a new command needs to go out —
 * this is the one place the whole payment lifecycle can be reasoned about.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);
    private static final String COMMANDS_TOPIC = "payment.commands";
    private static final String EVENTS_TOPIC = "payment.events";

    private final PaymentEngineEventStore paymentEngineEventStore;
    private final ProcessedEventRepository processedEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public PaymentEventListener(PaymentEngineEventStore paymentEngineEventStore,
                                 ProcessedEventRepository processedEventRepository,
                                 KafkaTemplate<String, String> kafkaTemplate,
                                 ObjectMapper objectMapper) {
        this.paymentEngineEventStore = paymentEngineEventStore;
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

        handle(envelope);
        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), Instant.now()));
        ack.acknowledge();
    }

    // Invoked once Resilience4j's bounded, backed-off retries (see
    // application.yml's resilience4j.retry.instances.kafka-consumer) are
    // exhausted. No ack here either — the container will redeliver. Once
    // processed_events catches up, the retry is a safe no-op, not a
    // double-apply.
    private void onEventProcessingFailed(ConsumerRecord<String, String> record, Acknowledgment ack, Throwable t) {
        log.error("Failed to process payment event after retries exhausted, will redeliver", t);
    }

    private void handle(EventEnvelope envelope) throws Exception {
        PaymentEventType type = PaymentEventType.valueOf(envelope.eventType());

        switch (type) {
            case PAYMENT_INITIATED -> onPaymentInitiated(envelope);
            case COMPLIANCE_APPROVED -> onComplianceApproved(envelope);
            case COMPLIANCE_REJECTED -> onComplianceRejected(envelope);
            case FRAUD_APPROVED -> onFraudApproved(envelope);
            case FRAUD_REJECTED -> onFraudRejected(envelope);
            case FUNDS_AUTHORIZED -> onFundsAuthorized(envelope);
            case FUNDS_AUTHORIZATION_FAILED -> onFundsAuthorizationFailed(envelope);
            case LEDGER_POSTED -> onLedgerPosted(envelope);
            case PAYMENT_SETTLED -> onPaymentSettled(envelope);
            case LEDGER_FINALIZED -> onLedgerFinalized(envelope);
            case SETTLEMENT_DECLINED -> onSettlementDeclined(envelope);
            case LEDGER_REVERSED -> onLedgerReversed(envelope);
            case FUNDS_RELEASED -> onFundsReleased(envelope);
            default -> log.info("No handling wired up yet for {}", type);
        }
    }

    private void onPaymentInitiated(EventEnvelope envelope) throws Exception {
        PaymentInitiatedEvent event = objectMapper.treeToValue(envelope.payload(), PaymentInitiatedEvent.class);

        paymentEngineEventStore.appendInitiated(event.paymentId(), event.payerAccount(), event.payeeAccount(),
                event.amountCents(), event.currency(), event.paymentMethod().name(), Instant.now());

        CheckComplianceCommand command = new CheckComplianceCommand(
                event.paymentId(), event.payerAccount(), event.payeeAccount(), event.amountCents(),
                event.currency(), event.paymentMethod(), Instant.now());
        publishCommand(event.paymentId(), "CHECK_COMPLIANCE", command);

        log.info("Payment {} INITIATED -> issued CHECK_COMPLIANCE", event.paymentId());
    }

    private void onComplianceApproved(EventEnvelope envelope) throws Exception {
        UUID paymentId = envelope.aggregateId();
        PaymentEngineAggregate aggregate = paymentEngineEventStore.load(paymentId).orElse(null);
        if (aggregate == null) {
            log.warn("Received COMPLIANCE_APPROVED for unknown payment {}", paymentId);
            return;
        }

        paymentEngineEventStore.append(aggregate, "COMPLIANCE_APPROVED", PaymentState.COMPLIANCE_CHECKED, Instant.now());

        CheckFraudCommand command = new CheckFraudCommand(
                paymentId, aggregate.getPayerAccount(), aggregate.getAmountCents(), aggregate.getCurrency(), Instant.now());
        publishCommand(paymentId, "CHECK_FRAUD", command);
        log.info("Payment {} COMPLIANCE_CHECKED -> issued CHECK_FRAUD", paymentId);
    }

    private void onComplianceRejected(EventEnvelope envelope) throws Exception {
        ComplianceRejectedEvent event = objectMapper.treeToValue(envelope.payload(), ComplianceRejectedEvent.class);
        failPayment(event.paymentId(), "COMPLIANCE_REJECTED", event.reason(), "compliance check rejected");
    }

    private void onFraudApproved(EventEnvelope envelope) throws Exception {
        UUID paymentId = envelope.aggregateId();
        PaymentEngineAggregate aggregate = paymentEngineEventStore.load(paymentId).orElse(null);
        if (aggregate == null) {
            log.warn("Received FRAUD_APPROVED for unknown payment {}", paymentId);
            return;
        }

        paymentEngineEventStore.append(aggregate, "FRAUD_APPROVED", PaymentState.FRAUD_CHECKED, Instant.now());

        AuthorizeFundsCommand command = new AuthorizeFundsCommand(
                paymentId, aggregate.getPayerAccount(), aggregate.getAmountCents(), aggregate.getCurrency(), Instant.now());
        publishCommand(paymentId, "AUTHORIZE_FUNDS", command);
        log.info("Payment {} FRAUD_CHECKED -> issued AUTHORIZE_FUNDS", paymentId);
    }

    private void onFraudRejected(EventEnvelope envelope) throws Exception {
        FraudRejectedEvent event = objectMapper.treeToValue(envelope.payload(), FraudRejectedEvent.class);
        failPayment(event.paymentId(), "FRAUD_REJECTED", event.reason(), "fraud rejected");
    }

    private void onFundsAuthorized(EventEnvelope envelope) throws Exception {
        UUID paymentId = envelope.aggregateId();
        PaymentEngineAggregate aggregate = paymentEngineEventStore.load(paymentId).orElse(null);
        if (aggregate == null) {
            log.warn("Received FUNDS_AUTHORIZED for unknown payment {}", paymentId);
            return;
        }

        paymentEngineEventStore.append(aggregate, "FUNDS_AUTHORIZED", PaymentState.AUTHORIZED, Instant.now());

        PostLedgerCommand command = new PostLedgerCommand(
                paymentId, aggregate.getPayerAccount(), aggregate.getPayeeAccount(),
                aggregate.getAmountCents(), aggregate.getCurrency(), Instant.now());
        publishCommand(paymentId, "POST_LEDGER", command);
        log.info("Payment {} AUTHORIZED -> issued POST_LEDGER", paymentId);
    }

    private void onLedgerPosted(EventEnvelope envelope) throws Exception {
        UUID paymentId = envelope.aggregateId();
        PaymentEngineAggregate aggregate = paymentEngineEventStore.load(paymentId).orElse(null);
        if (aggregate == null) {
            log.warn("Received LEDGER_POSTED for unknown payment {}", paymentId);
            return;
        }

        paymentEngineEventStore.append(aggregate, "LEDGER_POSTED", PaymentState.LEDGER_POSTED, Instant.now());

        SettleCommand command = new SettleCommand(
                paymentId, aggregate.getPayerAccount(), aggregate.getPayeeAccount(),
                aggregate.getAmountCents(), aggregate.getCurrency(), Instant.now());
        publishCommand(paymentId, "SETTLE", command);
        log.info("Payment {} LEDGER_POSTED -> issued SETTLE", paymentId);
    }

    private void onPaymentSettled(EventEnvelope envelope) throws Exception {
        UUID paymentId = envelope.aggregateId();
        PaymentEngineAggregate aggregate = paymentEngineEventStore.load(paymentId).orElse(null);
        if (aggregate == null) {
            log.warn("Received PAYMENT_SETTLED for unknown payment {}", paymentId);
            return;
        }

        // SETTLED is terminal from the payer/payee's perspective the moment
        // capture is confirmed. Converting the ledger's HOLD into a FINAL
        // posting is bookkeeping that follows behind it, not a gate on
        // reaching this state -- mirrors how real settlement and ledger
        // close can lag each other slightly.
        paymentEngineEventStore.append(aggregate, "PAYMENT_SETTLED", PaymentState.SETTLED, Instant.now());

        PostFinalLedgerCommand command = new PostFinalLedgerCommand(
                paymentId, aggregate.getPayeeAccount(), aggregate.getAmountCents(), Instant.now());
        publishCommand(paymentId, "POST_FINAL_LEDGER", command);
        log.info("Payment {} SETTLED -> issued POST_FINAL_LEDGER", paymentId);
    }

    private void onLedgerFinalized(EventEnvelope envelope) {
        log.info("Payment {} ledger FINAL leg confirmed (informational — does not change payment state)",
                envelope.aggregateId());
    }

    private void onFundsAuthorizationFailed(EventEnvelope envelope) throws Exception {
        FundsAuthorizationFailedEvent event =
                objectMapper.treeToValue(envelope.payload(), FundsAuthorizationFailedEvent.class);
        failPayment(event.paymentId(), "FUNDS_AUTHORIZATION_FAILED", event.reason(), "funds authorization rejected");
    }

    // --- compensation path -----------------------------------------------
    // Settlement is the only step so far that can decline after funds were
    // already authorized and a ledger HOLD already posted -- everything
    // upstream of it fails before touching money. Compensating actions run
    // in reverse order of how they were originally applied: reverse the
    // ledger first (the most recent side effect), then release the funds
    // reservation (the earlier one).

    private void onSettlementDeclined(EventEnvelope envelope) throws Exception {
        SettlementDeclinedEvent event = objectMapper.treeToValue(envelope.payload(), SettlementDeclinedEvent.class);
        PaymentEngineAggregate aggregate = paymentEngineEventStore.load(event.paymentId()).orElse(null);
        if (aggregate == null) {
            log.warn("Received SETTLEMENT_DECLINED for unknown payment {}", event.paymentId());
            return;
        }

        paymentEngineEventStore.append(aggregate, "SETTLEMENT_DECLINED", PaymentState.COMPENSATING, Instant.now());
        log.info("Payment {} COMPENSATING — settlement declined: {}", event.paymentId(), event.reason());

        ReverseLedgerCommand command = new ReverseLedgerCommand(
                event.paymentId(), aggregate.getPayerAccount(), aggregate.getAmountCents(), Instant.now());
        publishCommand(event.paymentId(), "REVERSE_LEDGER", command);
        log.info("Payment {} COMPENSATING -> issued REVERSE_LEDGER", event.paymentId());
    }

    private void onLedgerReversed(EventEnvelope envelope) throws Exception {
        UUID paymentId = envelope.aggregateId();
        PaymentEngineAggregate aggregate = paymentEngineEventStore.load(paymentId).orElse(null);
        if (aggregate == null) {
            log.warn("Received LEDGER_REVERSED for unknown payment {}", paymentId);
            return;
        }

        // State stays COMPENSATING -- reversing the ledger is the first of
        // two compensating actions; it only reaches COMPENSATED once the
        // funds reservation is released too.
        ReleaseFundsCommand command = new ReleaseFundsCommand(
                paymentId, aggregate.getPayerAccount(), aggregate.getAmountCents(), Instant.now());
        publishCommand(paymentId, "RELEASE_FUNDS", command);
        log.info("Payment {} ledger reversed -> issued RELEASE_FUNDS", paymentId);
    }

    private void onFundsReleased(EventEnvelope envelope) throws Exception {
        UUID paymentId = envelope.aggregateId();
        PaymentEngineAggregate aggregate = paymentEngineEventStore.load(paymentId).orElse(null);
        if (aggregate == null) {
            log.warn("Received FUNDS_RELEASED for unknown payment {}", paymentId);
            return;
        }

        paymentEngineEventStore.append(aggregate, "FUNDS_RELEASED", PaymentState.COMPENSATED, Instant.now());
        log.info("Payment {} COMPENSATED — compensation complete, funds returned to payer", paymentId);

        publishEvent(paymentId, PaymentEventType.PAYMENT_COMPENSATED,
                new PaymentCompensatedEvent(paymentId, aggregate.getPayerAccount(), Instant.now()));
    }

    private void failPayment(UUID paymentId, String triggerEventType, String reason, String logLabel) throws Exception {
        PaymentEngineAggregate aggregate = paymentEngineEventStore.load(paymentId).orElse(null);
        if (aggregate == null) {
            log.warn("Received a failure signal for unknown payment {}", paymentId);
            return;
        }

        paymentEngineEventStore.append(aggregate, triggerEventType, PaymentState.FAILED, Instant.now());
        log.info("Payment {} FAILED — {}: {}", paymentId, logLabel, reason);

        publishEvent(paymentId, PaymentEventType.PAYMENT_FAILED,
                new PaymentFailedEvent(paymentId, aggregate.getPayerAccount(), reason, Instant.now()));
    }

    private void publishCommand(UUID paymentId, String commandType, Object payload) throws Exception {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                paymentId,
                commandType,
                Instant.now(),
                objectMapper.valueToTree(payload)
        );
        kafkaTemplate.send(COMMANDS_TOPIC, paymentId.toString(), objectMapper.writeValueAsString(envelope)).get();
    }

    private void publishEvent(UUID paymentId, PaymentEventType type, Object payload) throws Exception {
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
