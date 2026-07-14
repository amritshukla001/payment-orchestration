package com.payflow.orchestrator.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.payflow.common.commands.AuthorizeFundsCommand;
import com.payflow.common.commands.CheckFraudCommand;
import com.payflow.common.commands.PostFinalLedgerCommand;
import com.payflow.common.commands.PostLedgerCommand;
import com.payflow.common.commands.SettleCommand;
import com.payflow.common.enums.PaymentState;
import com.payflow.common.events.*;
import com.payflow.orchestrator.domain.PaymentSagaState;
import com.payflow.orchestrator.repository.PaymentSagaStateRepository;
import com.payflow.orchestrator.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * This is the regression pack for the saga's state machine — every path
 * here mirrors a scenario previously verified by hand with curl + psql
 * across each build phase. A future change to the orchestrator that
 * silently breaks one of these transitions fails CI instead of shipping.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventListenerTest {

    @Mock
    private PaymentSagaStateRepository sagaStateRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private Acknowledgment ack;

    private ObjectMapper objectMapper;
    private PaymentEventListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new PaymentEventListener(sagaStateRepository, processedEventRepository, kafkaTemplate, objectMapper);
        lenient().when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void paymentInitiatedSavesStateAndIssuesCheckFraud() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID payerAccount = UUID.randomUUID();
        UUID payeeAccount = UUID.randomUUID();
        PaymentInitiatedEvent event = new PaymentInitiatedEvent(
                paymentId, payerAccount, payeeAccount, 5_000L, "USD", Instant.now());

        listener.onEvent(recordFor(paymentId, PaymentEventType.PAYMENT_INITIATED, event), ack);

        ArgumentCaptor<PaymentSagaState> stateCaptor = ArgumentCaptor.forClass(PaymentSagaState.class);
        verify(sagaStateRepository).save(stateCaptor.capture());
        PaymentSagaState saved = stateCaptor.getValue();
        assertThat(saved.getPaymentId()).isEqualTo(paymentId);
        assertThat(saved.getPayerAccount()).isEqualTo(payerAccount);
        assertThat(saved.getPayeeAccount()).isEqualTo(payeeAccount);
        assertThat(saved.getState()).isEqualTo(PaymentState.INITIATED);

        CheckFraudCommand command = capturedCommand(paymentId, "CHECK_FRAUD", CheckFraudCommand.class);
        assertThat(command.payerAccount()).isEqualTo(payerAccount);
        assertThat(command.amountCents()).isEqualTo(5_000L);

        verify(ack).acknowledge();
    }

    @Test
    void fraudApprovedAdvancesToFraudCheckedAndIssuesAuthorizeFunds() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSagaState existing = existingState(paymentId, PaymentState.INITIATED);
        when(sagaStateRepository.findById(paymentId)).thenReturn(Optional.of(existing));

        listener.onEvent(recordFor(paymentId, PaymentEventType.FRAUD_APPROVED,
                new FraudApprovedEvent(paymentId, Instant.now())), ack);

        assertThat(existing.getState()).isEqualTo(PaymentState.FRAUD_CHECKED);
        verify(sagaStateRepository).save(existing);

        AuthorizeFundsCommand command = capturedCommand(paymentId, "AUTHORIZE_FUNDS", AuthorizeFundsCommand.class);
        assertThat(command.payerAccount()).isEqualTo(existing.getPayerAccount());
    }

    @Test
    void fraudRejectedEndsTheSagaInFailedAndPublishesPaymentFailed() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSagaState existing = existingState(paymentId, PaymentState.INITIATED);
        when(sagaStateRepository.findById(paymentId)).thenReturn(Optional.of(existing));

        listener.onEvent(recordFor(paymentId, PaymentEventType.FRAUD_REJECTED,
                new FraudRejectedEvent(paymentId, "over threshold", Instant.now())), ack);

        assertThat(existing.getState()).isEqualTo(PaymentState.FAILED);
        PaymentFailedEvent event = capturedEvent(paymentId, "PAYMENT_FAILED", PaymentFailedEvent.class);
        assertThat(event.payerAccount()).isEqualTo(existing.getPayerAccount());
        assertThat(event.reason()).isEqualTo("over threshold");
    }

    @Test
    void fundsAuthorizedAdvancesToAuthorizedAndIssuesPostLedger() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSagaState existing = existingState(paymentId, PaymentState.FRAUD_CHECKED);
        when(sagaStateRepository.findById(paymentId)).thenReturn(Optional.of(existing));

        listener.onEvent(recordFor(paymentId, PaymentEventType.FUNDS_AUTHORIZED,
                new FundsAuthorizedEvent(paymentId, Instant.now())), ack);

        assertThat(existing.getState()).isEqualTo(PaymentState.AUTHORIZED);
        PostLedgerCommand command = capturedCommand(paymentId, "POST_LEDGER", PostLedgerCommand.class);
        assertThat(command.payeeAccount()).isEqualTo(existing.getPayeeAccount());
    }

    @Test
    void fundsAuthorizationFailedEndsTheSagaInFailedAndPublishesPaymentFailed() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSagaState existing = existingState(paymentId, PaymentState.FRAUD_CHECKED);
        when(sagaStateRepository.findById(paymentId)).thenReturn(Optional.of(existing));

        listener.onEvent(recordFor(paymentId, PaymentEventType.FUNDS_AUTHORIZATION_FAILED,
                new FundsAuthorizationFailedEvent(paymentId, "insufficient funds", Instant.now())), ack);

        assertThat(existing.getState()).isEqualTo(PaymentState.FAILED);
        PaymentFailedEvent event = capturedEvent(paymentId, "PAYMENT_FAILED", PaymentFailedEvent.class);
        assertThat(event.payerAccount()).isEqualTo(existing.getPayerAccount());
        assertThat(event.reason()).isEqualTo("insufficient funds");
    }

    @Test
    void ledgerPostedAdvancesToLedgerPostedAndIssuesSettle() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSagaState existing = existingState(paymentId, PaymentState.AUTHORIZED);
        when(sagaStateRepository.findById(paymentId)).thenReturn(Optional.of(existing));

        listener.onEvent(recordFor(paymentId, PaymentEventType.LEDGER_POSTED,
                new LedgerPostedEvent(paymentId, Instant.now())), ack);

        assertThat(existing.getState()).isEqualTo(PaymentState.LEDGER_POSTED);
        SettleCommand command = capturedCommand(paymentId, "SETTLE", SettleCommand.class);
        assertThat(command.payeeAccount()).isEqualTo(existing.getPayeeAccount());
    }

    @Test
    void paymentSettledAdvancesToSettledAndIssuesPostFinalLedger() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSagaState existing = existingState(paymentId, PaymentState.LEDGER_POSTED);
        when(sagaStateRepository.findById(paymentId)).thenReturn(Optional.of(existing));

        listener.onEvent(recordFor(paymentId, PaymentEventType.PAYMENT_SETTLED,
                new PaymentSettledEvent(paymentId, existing.getPayerAccount(), existing.getPayeeAccount(), Instant.now())), ack);

        assertThat(existing.getState()).isEqualTo(PaymentState.SETTLED);
        PostFinalLedgerCommand command = capturedCommand(paymentId, "POST_FINAL_LEDGER", PostFinalLedgerCommand.class);
        assertThat(command.payeeAccount()).isEqualTo(existing.getPayeeAccount());
    }

    @Test
    void ledgerFinalizedIsInformationalAndDoesNotChangeSagaState() throws Exception {
        UUID paymentId = UUID.randomUUID();
        // LEDGER_FINALIZED is deliberately not looked up in the repository at
        // all -- it's a pure audit-log signal, so no findById should even happen.

        listener.onEvent(recordFor(paymentId, PaymentEventType.LEDGER_FINALIZED,
                new LedgerFinalizedEvent(paymentId, Instant.now())), ack);

        verifyNoInteractions(sagaStateRepository);
        verifyNoInteractions(kafkaTemplate);
        verify(ack).acknowledge();
    }

    @Test
    void anAlreadyProcessedEventIsSkippedEntirely() throws Exception {
        UUID paymentId = UUID.randomUUID();
        ConsumerRecord<String, String> record = recordFor(paymentId, PaymentEventType.PAYMENT_INITIATED,
                new PaymentInitiatedEvent(paymentId, UUID.randomUUID(), UUID.randomUUID(), 100L, "USD", Instant.now()));
        UUID eventId = envelopeIdOf(record);
        when(processedEventRepository.existsById(eventId)).thenReturn(true);

        listener.onEvent(record, ack);

        verifyNoInteractions(sagaStateRepository);
        verifyNoInteractions(kafkaTemplate);
        verify(ack).acknowledge();
    }

    @Test
    void anEventForAnUnknownPaymentIsLoggedAndSkippedWithoutThrowing() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(sagaStateRepository.findById(paymentId)).thenReturn(Optional.empty());

        listener.onEvent(recordFor(paymentId, PaymentEventType.FRAUD_APPROVED,
                new FraudApprovedEvent(paymentId, Instant.now())), ack);

        verify(sagaStateRepository, never()).save(any());
        verifyNoInteractions(kafkaTemplate);
        verify(ack).acknowledge();
    }

    // --- helpers -------------------------------------------------------

    private PaymentSagaState existingState(UUID paymentId, PaymentState state) {
        return new PaymentSagaState(paymentId, UUID.randomUUID(), UUID.randomUUID(), 5_000L, "USD", state, Instant.now());
    }

    private <T> ConsumerRecord<String, String> recordFor(UUID paymentId, PaymentEventType type, T payload) throws Exception {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), paymentId, type.name(), Instant.now(), objectMapper.valueToTree(payload));
        return new ConsumerRecord<>("payment.events", 0, 0L, paymentId.toString(),
                objectMapper.writeValueAsString(envelope));
    }

    private UUID envelopeIdOf(ConsumerRecord<String, String> record) throws Exception {
        return objectMapper.readValue(record.value(), EventEnvelope.class).eventId();
    }

    private <T> T capturedCommand(UUID paymentId, String expectedType, Class<T> payloadType) throws Exception {
        return capturedMessage("payment.commands", paymentId, expectedType, payloadType);
    }

    private <T> T capturedEvent(UUID paymentId, String expectedType, Class<T> payloadType) throws Exception {
        return capturedMessage("payment.events", paymentId, expectedType, payloadType);
    }

    private <T> T capturedMessage(String topic, UUID paymentId, String expectedType, Class<T> payloadType) throws Exception {
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(topic), eq(paymentId.toString()), jsonCaptor.capture());
        EventEnvelope envelope = objectMapper.readValue(jsonCaptor.getValue(), EventEnvelope.class);
        assertThat(envelope.eventType()).isEqualTo(expectedType);
        JsonNode payload = envelope.payload();
        return objectMapper.treeToValue(payload, payloadType);
    }
}
