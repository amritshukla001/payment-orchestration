package com.payflow.orchestrator.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.commands.AuthorizeFundsCommand;
import com.payflow.common.commands.CheckFraudCommand;
import com.payflow.common.enums.PaymentState;
import com.payflow.common.events.EventEnvelope;
import com.payflow.common.events.FraudRejectedEvent;
import com.payflow.common.events.FundsAuthorizationFailedEvent;
import com.payflow.common.events.PaymentEventType;
import com.payflow.common.events.PaymentInitiatedEvent;
import com.payflow.orchestrator.domain.PaymentSagaState;
import com.payflow.orchestrator.domain.ProcessedEvent;
import com.payflow.orchestrator.repository.PaymentSagaStateRepository;
import com.payflow.orchestrator.repository.ProcessedEventRepository;
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
 * Consumes the domain-event stream and drives the saga state machine.
 * On each new fact, decides whether a new command needs to go out —
 * this is the one place the whole payment lifecycle can be reasoned about.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);
    private static final String COMMANDS_TOPIC = "payment.commands";

    private final PaymentSagaStateRepository sagaStateRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public PaymentEventListener(PaymentSagaStateRepository sagaStateRepository,
                                 ProcessedEventRepository processedEventRepository,
                                 KafkaTemplate<String, String> kafkaTemplate,
                                 ObjectMapper objectMapper) {
        this.sagaStateRepository = sagaStateRepository;
        this.processedEventRepository = processedEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "payment.events", containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            EventEnvelope envelope = objectMapper.readValue(record.value(), EventEnvelope.class);

            if (processedEventRepository.existsById(envelope.eventId())) {
                log.debug("Skipping already-processed event {}", envelope.eventId());
                ack.acknowledge();
                return;
            }

            handle(envelope);
            processedEventRepository.save(new ProcessedEvent(envelope.eventId(), Instant.now()));
            ack.acknowledge();
        } catch (Exception e) {
            // No ack — the container will redeliver. Once processed_events
            // catches up, the retry is a safe no-op, not a double-apply.
            log.error("Failed to process payment event, will redeliver", e);
        }
    }

    private void handle(EventEnvelope envelope) throws Exception {
        PaymentEventType type = PaymentEventType.valueOf(envelope.eventType());

        switch (type) {
            case PAYMENT_INITIATED -> onPaymentInitiated(envelope);
            case FRAUD_APPROVED -> onFraudApproved(envelope);
            case FRAUD_REJECTED -> onFraudRejected(envelope);
            case FUNDS_AUTHORIZED -> onFundsAuthorized(envelope);
            case FUNDS_AUTHORIZATION_FAILED -> onFundsAuthorizationFailed(envelope);
            default -> log.info("No saga handling wired up yet for {}", type);
        }
    }

    private void onPaymentInitiated(EventEnvelope envelope) throws Exception {
        PaymentInitiatedEvent event = objectMapper.treeToValue(envelope.payload(), PaymentInitiatedEvent.class);

        PaymentSagaState state = new PaymentSagaState(
                event.paymentId(), event.payerAccount(), event.amountCents(), event.currency(),
                PaymentState.INITIATED, Instant.now());
        sagaStateRepository.save(state);

        CheckFraudCommand command = new CheckFraudCommand(
                event.paymentId(), event.payerAccount(), event.amountCents(), event.currency(), Instant.now());
        publishCommand(event.paymentId(), "CHECK_FRAUD", command);

        log.info("Payment {} INITIATED -> issued CHECK_FRAUD", event.paymentId());
    }

    private void onFraudApproved(EventEnvelope envelope) throws Exception {
        UUID paymentId = envelope.aggregateId();
        PaymentSagaState state = sagaStateRepository.findById(paymentId).orElse(null);
        if (state == null) {
            log.warn("Received FRAUD_APPROVED for unknown payment {}", paymentId);
            return;
        }

        state.advanceTo(PaymentState.FRAUD_CHECKED, Instant.now());
        sagaStateRepository.save(state);

        AuthorizeFundsCommand command = new AuthorizeFundsCommand(
                paymentId, state.getPayerAccount(), state.getAmountCents(), state.getCurrency(), Instant.now());
        publishCommand(paymentId, "AUTHORIZE_FUNDS", command);
        log.info("Payment {} FRAUD_CHECKED -> issued AUTHORIZE_FUNDS", paymentId);
    }

    private void onFraudRejected(EventEnvelope envelope) throws Exception {
        FraudRejectedEvent event = objectMapper.treeToValue(envelope.payload(), FraudRejectedEvent.class);
        sagaStateRepository.findById(event.paymentId()).ifPresentOrElse(
                state -> {
                    state.advanceTo(PaymentState.FAILED, Instant.now());
                    sagaStateRepository.save(state);
                    log.info("Payment {} FAILED — fraud rejected: {}", event.paymentId(), event.reason());
                },
                () -> log.warn("Received FRAUD_REJECTED for unknown payment {}", event.paymentId())
        );
    }

    private void onFundsAuthorized(EventEnvelope envelope) {
        UUID paymentId = envelope.aggregateId();
        sagaStateRepository.findById(paymentId).ifPresentOrElse(
                state -> {
                    state.advanceTo(PaymentState.AUTHORIZED, Instant.now());
                    sagaStateRepository.save(state);
                    log.info("Payment {} advanced to AUTHORIZED (ledger-service not built yet — saga pauses here)",
                            paymentId);
                },
                () -> log.warn("Received FUNDS_AUTHORIZED for unknown payment {}", paymentId)
        );
    }

    private void onFundsAuthorizationFailed(EventEnvelope envelope) throws Exception {
        FundsAuthorizationFailedEvent event =
                objectMapper.treeToValue(envelope.payload(), FundsAuthorizationFailedEvent.class);
        sagaStateRepository.findById(event.paymentId()).ifPresentOrElse(
                state -> {
                    state.advanceTo(PaymentState.FAILED, Instant.now());
                    sagaStateRepository.save(state);
                    log.info("Payment {} FAILED — funds authorization rejected: {}",
                            event.paymentId(), event.reason());
                },
                () -> log.warn("Received FUNDS_AUTHORIZATION_FAILED for unknown payment {}", event.paymentId())
        );
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
}
