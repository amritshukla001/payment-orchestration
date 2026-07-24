package com.payflow.orchestrator.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.payflow.common.commands.AuthorizeFundsCommand;
import com.payflow.common.commands.CheckFraudCommand;
import com.payflow.common.commands.PostFinalLedgerCommand;
import com.payflow.common.commands.PostLedgerCommand;
import com.payflow.common.commands.ReleaseFundsCommand;
import com.payflow.common.commands.ReverseLedgerCommand;
import com.payflow.common.commands.SettleCommand;
import com.payflow.common.enums.PaymentState;
import com.payflow.common.events.*;
import com.payflow.orchestrator.domain.PaymentSagaAggregate;
import com.payflow.orchestrator.domain.SagaEventStore;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * This is the regression pack for the saga's state machine — every path
 * here mirrors a scenario previously verified by hand with curl + psql
 * across each build phase. A future change to the orchestrator that
 * silently breaks one of these transitions fails CI instead of shipping.
 * Assertions here verify the correct fact was appended to the event log
 * (via SagaEventStore) rather than inspecting a mutated entity — the
 * store's own fold/append correctness is covered separately by
 * SagaEventStoreTest and PaymentSagaAggregateTest.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventListenerTest {

    @Mock
    private SagaEventStore sagaEventStore;
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
        listener = new PaymentEventListener(sagaEventStore, processedEventRepository, kafkaTemplate, objectMapper);
        lenient().when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void paymentInitiatedAppendsTheInitialFactAndIssuesCheckFraud() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID payerAccount = UUID.randomUUID();
        UUID payeeAccount = UUID.randomUUID();
        PaymentInitiatedEvent event = new PaymentInitiatedEvent(
                paymentId, payerAccount, payeeAccount, 5_000L, "USD", Instant.now());

        listener.onEvent(recordFor(paymentId, PaymentEventType.PAYMENT_INITIATED, event), ack);

        verify(sagaEventStore).appendInitiated(eq(paymentId), eq(payerAccount), eq(payeeAccount),
                eq(5_000L), eq("USD"), any(Instant.class));

        CheckFraudCommand command = capturedCommand(paymentId, "CHECK_FRAUD", CheckFraudCommand.class);
        assertThat(command.payerAccount()).isEqualTo(payerAccount);
        assertThat(command.amountCents()).isEqualTo(5_000L);

        verify(ack).acknowledge();
    }

    @Test
    void fraudApprovedAppendsFraudCheckedAndIssuesAuthorizeFunds() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSagaAggregate existing = existingAggregate(paymentId, PaymentState.INITIATED);
        when(sagaEventStore.load(paymentId)).thenReturn(Optional.of(existing));

        listener.onEvent(recordFor(paymentId, PaymentEventType.FRAUD_APPROVED,
                new FraudApprovedEvent(paymentId, Instant.now())), ack);

        verify(sagaEventStore).append(same(existing), eq("FRAUD_APPROVED"), eq(PaymentState.FRAUD_CHECKED), any(Instant.class));

        AuthorizeFundsCommand command = capturedCommand(paymentId, "AUTHORIZE_FUNDS", AuthorizeFundsCommand.class);
        assertThat(command.payerAccount()).isEqualTo(existing.getPayerAccount());
    }

    @Test
    void fraudRejectedAppendsFailedAndPublishesPaymentFailed() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSagaAggregate existing = existingAggregate(paymentId, PaymentState.INITIATED);
        when(sagaEventStore.load(paymentId)).thenReturn(Optional.of(existing));

        listener.onEvent(recordFor(paymentId, PaymentEventType.FRAUD_REJECTED,
                new FraudRejectedEvent(paymentId, "over threshold", Instant.now())), ack);

        verify(sagaEventStore).append(same(existing), eq("FRAUD_REJECTED"), eq(PaymentState.FAILED), any(Instant.class));
        PaymentFailedEvent event = capturedEvent(paymentId, "PAYMENT_FAILED", PaymentFailedEvent.class);
        assertThat(event.payerAccount()).isEqualTo(existing.getPayerAccount());
        assertThat(event.reason()).isEqualTo("over threshold");
    }

    @Test
    void fundsAuthorizedAppendsAuthorizedAndIssuesPostLedger() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSagaAggregate existing = existingAggregate(paymentId, PaymentState.FRAUD_CHECKED);
        when(sagaEventStore.load(paymentId)).thenReturn(Optional.of(existing));

        listener.onEvent(recordFor(paymentId, PaymentEventType.FUNDS_AUTHORIZED,
                new FundsAuthorizedEvent(paymentId, Instant.now())), ack);

        verify(sagaEventStore).append(same(existing), eq("FUNDS_AUTHORIZED"), eq(PaymentState.AUTHORIZED), any(Instant.class));
        PostLedgerCommand command = capturedCommand(paymentId, "POST_LEDGER", PostLedgerCommand.class);
        assertThat(command.payeeAccount()).isEqualTo(existing.getPayeeAccount());
    }

    @Test
    void fundsAuthorizationFailedAppendsFailedAndPublishesPaymentFailed() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSagaAggregate existing = existingAggregate(paymentId, PaymentState.FRAUD_CHECKED);
        when(sagaEventStore.load(paymentId)).thenReturn(Optional.of(existing));

        listener.onEvent(recordFor(paymentId, PaymentEventType.FUNDS_AUTHORIZATION_FAILED,
                new FundsAuthorizationFailedEvent(paymentId, "insufficient funds", Instant.now())), ack);

        verify(sagaEventStore).append(same(existing), eq("FUNDS_AUTHORIZATION_FAILED"), eq(PaymentState.FAILED), any(Instant.class));
        PaymentFailedEvent event = capturedEvent(paymentId, "PAYMENT_FAILED", PaymentFailedEvent.class);
        assertThat(event.payerAccount()).isEqualTo(existing.getPayerAccount());
        assertThat(event.reason()).isEqualTo("insufficient funds");
    }

    @Test
    void ledgerPostedAppendsLedgerPostedAndIssuesSettle() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSagaAggregate existing = existingAggregate(paymentId, PaymentState.AUTHORIZED);
        when(sagaEventStore.load(paymentId)).thenReturn(Optional.of(existing));

        listener.onEvent(recordFor(paymentId, PaymentEventType.LEDGER_POSTED,
                new LedgerPostedEvent(UUID.randomUUID(), paymentId, UUID.randomUUID(), UUID.randomUUID(),
                        5_000L, "HOLD", Instant.now())), ack);

        verify(sagaEventStore).append(same(existing), eq("LEDGER_POSTED"), eq(PaymentState.LEDGER_POSTED), any(Instant.class));
        SettleCommand command = capturedCommand(paymentId, "SETTLE", SettleCommand.class);
        assertThat(command.payeeAccount()).isEqualTo(existing.getPayeeAccount());
    }

    @Test
    void paymentSettledAppendsSettledAndIssuesPostFinalLedger() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSagaAggregate existing = existingAggregate(paymentId, PaymentState.LEDGER_POSTED);
        when(sagaEventStore.load(paymentId)).thenReturn(Optional.of(existing));

        listener.onEvent(recordFor(paymentId, PaymentEventType.PAYMENT_SETTLED,
                new PaymentSettledEvent(paymentId, existing.getPayerAccount(), existing.getPayeeAccount(), Instant.now())), ack);

        verify(sagaEventStore).append(same(existing), eq("PAYMENT_SETTLED"), eq(PaymentState.SETTLED), any(Instant.class));
        PostFinalLedgerCommand command = capturedCommand(paymentId, "POST_FINAL_LEDGER", PostFinalLedgerCommand.class);
        assertThat(command.payeeAccount()).isEqualTo(existing.getPayeeAccount());
    }

    @Test
    void ledgerFinalizedIsInformationalAndDoesNotTouchTheEventLog() throws Exception {
        UUID paymentId = UUID.randomUUID();
        // LEDGER_FINALIZED is deliberately not looked up at all -- it's a
        // pure audit-log signal, so no load() should even happen.

        listener.onEvent(recordFor(paymentId, PaymentEventType.LEDGER_FINALIZED,
                new LedgerFinalizedEvent(UUID.randomUUID(), paymentId, UUID.randomUUID(), UUID.randomUUID(),
                        5_000L, "FINAL", Instant.now())), ack);

        verifyNoInteractions(sagaEventStore);
        verifyNoInteractions(kafkaTemplate);
        verify(ack).acknowledge();
    }

    @Test
    void settlementDeclinedAppendsCompensatingAndIssuesReverseLedger() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSagaAggregate existing = existingAggregate(paymentId, PaymentState.LEDGER_POSTED);
        when(sagaEventStore.load(paymentId)).thenReturn(Optional.of(existing));

        listener.onEvent(recordFor(paymentId, PaymentEventType.SETTLEMENT_DECLINED,
                new SettlementDeclinedEvent(paymentId, "issuer declined capture", Instant.now())), ack);

        verify(sagaEventStore).append(same(existing), eq("SETTLEMENT_DECLINED"), eq(PaymentState.COMPENSATING), any(Instant.class));
        ReverseLedgerCommand command = capturedCommand(paymentId, "REVERSE_LEDGER", ReverseLedgerCommand.class);
        assertThat(command.payerAccount()).isEqualTo(existing.getPayerAccount());
        assertThat(command.amountCents()).isEqualTo(existing.getAmountCents());
    }

    @Test
    void ledgerReversedDoesNotAppendAndIssuesReleaseFunds() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSagaAggregate existing = existingAggregate(paymentId, PaymentState.COMPENSATING);
        when(sagaEventStore.load(paymentId)).thenReturn(Optional.of(existing));

        listener.onEvent(recordFor(paymentId, PaymentEventType.LEDGER_REVERSED,
                new LedgerReversedEvent(UUID.randomUUID(), paymentId, UUID.randomUUID(), UUID.randomUUID(),
                        5_000L, "REVERSAL", Instant.now())), ack);

        // Compensation isn't complete yet -- releasing funds is the second
        // half, so no new fact is recorded and the state must not jump to
        // COMPENSATED prematurely.
        verify(sagaEventStore, never()).append(any(), any(), any(), any());
        ReleaseFundsCommand command = capturedCommand(paymentId, "RELEASE_FUNDS", ReleaseFundsCommand.class);
        assertThat(command.payerAccount()).isEqualTo(existing.getPayerAccount());
    }

    @Test
    void fundsReleasedAppendsCompensatedAndPublishesPaymentCompensated() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSagaAggregate existing = existingAggregate(paymentId, PaymentState.COMPENSATING);
        when(sagaEventStore.load(paymentId)).thenReturn(Optional.of(existing));

        listener.onEvent(recordFor(paymentId, PaymentEventType.FUNDS_RELEASED,
                new FundsReleasedEvent(paymentId, Instant.now())), ack);

        verify(sagaEventStore).append(same(existing), eq("FUNDS_RELEASED"), eq(PaymentState.COMPENSATED), any(Instant.class));
        PaymentCompensatedEvent event = capturedEvent(paymentId, "PAYMENT_COMPENSATED", PaymentCompensatedEvent.class);
        assertThat(event.payerAccount()).isEqualTo(existing.getPayerAccount());
    }

    @Test
    void fullCompensationSequenceEndsInCompensatedNotFailed() throws Exception {
        // The end-to-end shape of the compensation path, chained through
        // one saga aggregate exactly as the real Kafka round trips would --
        // proves the whole sequence lands on COMPENSATED, not stuck or
        // silently falling back to FAILED. LEDGER_REVERSED appends nothing,
        // so exactly two facts should be recorded: COMPENSATING, then
        // COMPENSATED.
        UUID paymentId = UUID.randomUUID();
        PaymentSagaAggregate existing = existingAggregate(paymentId, PaymentState.LEDGER_POSTED);
        when(sagaEventStore.load(paymentId)).thenReturn(Optional.of(existing));

        listener.onEvent(recordFor(paymentId, PaymentEventType.SETTLEMENT_DECLINED,
                new SettlementDeclinedEvent(paymentId, "issuer declined capture", Instant.now())), ack);
        listener.onEvent(recordFor(paymentId, PaymentEventType.LEDGER_REVERSED,
                new LedgerReversedEvent(UUID.randomUUID(), paymentId, UUID.randomUUID(), UUID.randomUUID(),
                        5_000L, "REVERSAL", Instant.now())), ack);
        listener.onEvent(recordFor(paymentId, PaymentEventType.FUNDS_RELEASED,
                new FundsReleasedEvent(paymentId, Instant.now())), ack);

        ArgumentCaptor<PaymentState> stateCaptor = ArgumentCaptor.forClass(PaymentState.class);
        verify(sagaEventStore, times(2)).append(same(existing), anyString(), stateCaptor.capture(), any(Instant.class));
        assertThat(stateCaptor.getAllValues()).containsExactly(PaymentState.COMPENSATING, PaymentState.COMPENSATED);
    }

    @Test
    void anAlreadyProcessedEventIsSkippedEntirely() throws Exception {
        UUID paymentId = UUID.randomUUID();
        ConsumerRecord<String, String> record = recordFor(paymentId, PaymentEventType.PAYMENT_INITIATED,
                new PaymentInitiatedEvent(paymentId, UUID.randomUUID(), UUID.randomUUID(), 100L, "USD", Instant.now()));
        UUID eventId = envelopeIdOf(record);
        when(processedEventRepository.existsById(eventId)).thenReturn(true);

        listener.onEvent(record, ack);

        verifyNoInteractions(sagaEventStore);
        verifyNoInteractions(kafkaTemplate);
        verify(ack).acknowledge();
    }

    @Test
    void anEventForAnUnknownPaymentIsLoggedAndSkippedWithoutThrowing() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(sagaEventStore.load(paymentId)).thenReturn(Optional.empty());

        listener.onEvent(recordFor(paymentId, PaymentEventType.FRAUD_APPROVED,
                new FraudApprovedEvent(paymentId, Instant.now())), ack);

        verify(sagaEventStore, never()).append(any(), any(), any(), any());
        verifyNoInteractions(kafkaTemplate);
        verify(ack).acknowledge();
    }

    @Test
    void aFailureDuringHandlingPropagatesInsteadOfBeingSwallowed() throws Exception {
        // Resilience4j's @Retry only retries a method that actually throws --
        // this proves a failure is visible to the caller instead of silently
        // logged. In the real app that caller is the Retry-then-Transactional
        // AOP proxy, not a plain-Mockito listener like this one, so this test
        // doesn't exercise retry/backoff itself -- it just characterizes that
        // a failure propagates rather than being swallowed at the method level.
        UUID paymentId = UUID.randomUUID();
        PaymentInitiatedEvent event = new PaymentInitiatedEvent(
                paymentId, UUID.randomUUID(), UUID.randomUUID(), 5_000L, "USD", Instant.now());
        doThrow(new RuntimeException("transient DB blip"))
                .when(sagaEventStore).appendInitiated(any(), any(), any(), anyLong(), any(), any());

        assertThatThrownBy(() -> listener.onEvent(recordFor(paymentId, PaymentEventType.PAYMENT_INITIATED, event), ack))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("transient DB blip");

        verify(ack, never()).acknowledge();
    }

    // --- helpers -------------------------------------------------------

    private PaymentSagaAggregate existingAggregate(UUID paymentId, PaymentState state) {
        return new PaymentSagaAggregate(paymentId, UUID.randomUUID(), UUID.randomUUID(), 5_000L, "USD", state, Instant.now());
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
