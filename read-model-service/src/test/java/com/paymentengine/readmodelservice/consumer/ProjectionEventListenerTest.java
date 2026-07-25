package com.paymentengine.readmodelservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.paymentengine.common.enums.PaymentMethod;
import com.paymentengine.common.enums.PaymentState;
import com.paymentengine.common.events.EventEnvelope;
import com.paymentengine.common.events.LedgerFinalizedEvent;
import com.paymentengine.common.events.LedgerPostedEvent;
import com.paymentengine.common.events.LedgerReversedEvent;
import com.paymentengine.common.events.NotificationSentEvent;
import com.paymentengine.common.events.PaymentInitiatedEvent;
import com.paymentengine.readmodelservice.domain.LedgerEntryView;
import com.paymentengine.readmodelservice.domain.NotificationView;
import com.paymentengine.readmodelservice.domain.PaymentView;
import com.paymentengine.readmodelservice.repository.LedgerEntryViewRepository;
import com.paymentengine.readmodelservice.repository.NotificationViewRepository;
import com.paymentengine.readmodelservice.repository.PaymentViewRepository;
import com.paymentengine.readmodelservice.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers all 4 documented saga flows (happy/SETTLED, fraud-reject,
 * insufficient-funds, compensation) plus ledger/notification detail
 * projection, idempotent redelivery, and the unknown-payment/failure-
 * propagation contracts every other listener in this codebase is held to.
 */
@ExtendWith(MockitoExtension.class)
class ProjectionEventListenerTest {

    @Mock
    private PaymentViewRepository paymentViewRepository;
    @Mock
    private LedgerEntryViewRepository ledgerEntryViewRepository;
    @Mock
    private NotificationViewRepository notificationViewRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private Acknowledgment ack;

    private ObjectMapper objectMapper;
    private ProjectionEventListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new ProjectionEventListener(paymentViewRepository, ledgerEntryViewRepository,
                notificationViewRepository, processedEventRepository, objectMapper);
    }

    @Test
    void paymentInitiatedCreatesTheViewInInitiatedState() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID payerAccount = UUID.randomUUID();
        UUID payeeAccount = UUID.randomUUID();
        PaymentInitiatedEvent event = new PaymentInitiatedEvent(
                paymentId, payerAccount, payeeAccount, 5_000L, "USD", PaymentMethod.NETBANKING, Instant.now());

        listener.onEvent(recordFor(paymentId, "PAYMENT_INITIATED", event), ack);

        ArgumentCaptor<PaymentView> captor = ArgumentCaptor.forClass(PaymentView.class);
        verify(paymentViewRepository).save(captor.capture());
        PaymentView saved = captor.getValue();
        assertThat(saved.getPaymentId()).isEqualTo(paymentId);
        assertThat(saved.getPayerAccount()).isEqualTo(payerAccount);
        assertThat(saved.getPayeeAccount()).isEqualTo(payeeAccount);
        assertThat(saved.getAmountCents()).isEqualTo(5_000L);
        assertThat(saved.getCurrency()).isEqualTo("USD");
        assertThat(saved.getPaymentMethod()).isEqualTo("NETBANKING");
        assertThat(saved.getState()).isEqualTo(PaymentState.INITIATED);
        verify(ack).acknowledge();
    }

    @Test
    void complianceApprovedAdvancesToComplianceChecked() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentView view = existingView(paymentId, PaymentState.INITIATED);
        when(paymentViewRepository.findById(paymentId)).thenReturn(Optional.of(view));

        listener.onEvent(recordFor(paymentId, "COMPLIANCE_APPROVED", Map.of()), ack);

        assertThat(view.getState()).isEqualTo(PaymentState.COMPLIANCE_CHECKED);
    }

    @Test
    void complianceRejectionEndsInFailed() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentView view = existingView(paymentId, PaymentState.INITIATED);
        when(paymentViewRepository.findById(paymentId)).thenReturn(Optional.of(view));

        listener.onEvent(recordFor(paymentId, "COMPLIANCE_REJECTED", Map.of()), ack);

        assertThat(view.getState()).isEqualTo(PaymentState.FAILED);
    }

    @Test
    void happyPathAdvancesThroughEveryStateToSettled() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentView view = existingView(paymentId, PaymentState.INITIATED);
        when(paymentViewRepository.findById(paymentId)).thenReturn(Optional.of(view));

        listener.onEvent(recordFor(paymentId, "FRAUD_APPROVED", Map.of()), ack);
        assertThat(view.getState()).isEqualTo(PaymentState.FRAUD_CHECKED);

        listener.onEvent(recordFor(paymentId, "FUNDS_AUTHORIZED", Map.of()), ack);
        assertThat(view.getState()).isEqualTo(PaymentState.AUTHORIZED);

        listener.onEvent(recordFor(paymentId, "LEDGER_POSTED", holdEvent(paymentId)), ack);
        assertThat(view.getState()).isEqualTo(PaymentState.LEDGER_POSTED);
        verify(ledgerEntryViewRepository).save(any(LedgerEntryView.class));

        listener.onEvent(recordFor(paymentId, "PAYMENT_SETTLED", Map.of()), ack);
        assertThat(view.getState()).isEqualTo(PaymentState.SETTLED);
    }

    @Test
    void ledgerFinalizedInsertsAFinalEntryWithoutTouchingState() throws Exception {
        UUID paymentId = UUID.randomUUID();
        LedgerFinalizedEvent event = new LedgerFinalizedEvent(UUID.randomUUID(), paymentId,
                UUID.randomUUID(), UUID.randomUUID(), 5_000L, "FINAL", Instant.now());

        listener.onEvent(recordFor(paymentId, "LEDGER_FINALIZED", event), ack);

        ArgumentCaptor<LedgerEntryView> captor = ArgumentCaptor.forClass(LedgerEntryView.class);
        verify(ledgerEntryViewRepository).save(captor.capture());
        assertThat(captor.getValue().getPostingType()).isEqualTo("FINAL");
        verifyNoInteractions(paymentViewRepository);
    }

    @Test
    void fraudRejectionEndsInFailed() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentView view = existingView(paymentId, PaymentState.INITIATED);
        when(paymentViewRepository.findById(paymentId)).thenReturn(Optional.of(view));

        listener.onEvent(recordFor(paymentId, "FRAUD_REJECTED", Map.of()), ack);

        assertThat(view.getState()).isEqualTo(PaymentState.FAILED);
    }

    @Test
    void insufficientFundsEndsInFailed() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentView view = existingView(paymentId, PaymentState.FRAUD_CHECKED);
        when(paymentViewRepository.findById(paymentId)).thenReturn(Optional.of(view));

        listener.onEvent(recordFor(paymentId, "FUNDS_AUTHORIZATION_FAILED", Map.of()), ack);

        assertThat(view.getState()).isEqualTo(PaymentState.FAILED);
    }

    @Test
    void compensationSequenceEndsInCompensatedWithAReversalEntry() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentView view = existingView(paymentId, PaymentState.LEDGER_POSTED);
        when(paymentViewRepository.findById(paymentId)).thenReturn(Optional.of(view));

        listener.onEvent(recordFor(paymentId, "SETTLEMENT_DECLINED", Map.of()), ack);
        assertThat(view.getState()).isEqualTo(PaymentState.COMPENSATING);

        LedgerReversedEvent reversed = new LedgerReversedEvent(UUID.randomUUID(), paymentId,
                UUID.randomUUID(), UUID.randomUUID(), 5_000L, "REVERSAL", Instant.now());
        listener.onEvent(recordFor(paymentId, "LEDGER_REVERSED", reversed), ack);
        assertThat(view.getState()).isEqualTo(PaymentState.COMPENSATING);
        ArgumentCaptor<LedgerEntryView> captor = ArgumentCaptor.forClass(LedgerEntryView.class);
        verify(ledgerEntryViewRepository).save(captor.capture());
        assertThat(captor.getValue().getPostingType()).isEqualTo("REVERSAL");

        // FUNDS_RELEASED is informational only here -- PAYMENT_COMPENSATED is
        // the authoritative terminal-state trigger for the read model.
        listener.onEvent(recordFor(paymentId, "FUNDS_RELEASED", Map.of()), ack);
        assertThat(view.getState()).isEqualTo(PaymentState.COMPENSATING);

        listener.onEvent(recordFor(paymentId, "PAYMENT_COMPENSATED", Map.of()), ack);
        assertThat(view.getState()).isEqualTo(PaymentState.COMPENSATED);
    }

    @Test
    void notificationSentInsertsANotificationViewRow() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        NotificationSentEvent event = new NotificationSentEvent(UUID.randomUUID(), paymentId, accountId,
                "PAYER", "SUCCESS", "Your payment has settled.", Instant.now());

        listener.onEvent(recordFor(paymentId, "NOTIFICATION_SENT", event), ack);

        ArgumentCaptor<NotificationView> captor = ArgumentCaptor.forClass(NotificationView.class);
        verify(notificationViewRepository).save(captor.capture());
        assertThat(captor.getValue().getAccountId()).isEqualTo(accountId);
        assertThat(captor.getValue().getRecipient()).isEqualTo("PAYER");
    }

    @Test
    void anEventForAnUnknownPaymentIsLoggedAndSkippedWithoutThrowing() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentViewRepository.findById(paymentId)).thenReturn(Optional.empty());

        listener.onEvent(recordFor(paymentId, "FRAUD_APPROVED", Map.of()), ack);

        verify(paymentViewRepository, never()).save(any());
        verify(ack).acknowledge();
    }

    @Test
    void anAlreadyProcessedEventIsSkippedEntirely() throws Exception {
        UUID paymentId = UUID.randomUUID();
        ConsumerRecord<String, String> record = recordFor(paymentId, "PAYMENT_INITIATED",
                new PaymentInitiatedEvent(paymentId, UUID.randomUUID(), UUID.randomUUID(), 100L, "USD",
                        PaymentMethod.NETBANKING, Instant.now()));
        UUID eventId = objectMapper.readValue(record.value(), EventEnvelope.class).eventId();
        when(processedEventRepository.existsById(eventId)).thenReturn(true);

        listener.onEvent(record, ack);

        verifyNoInteractions(paymentViewRepository);
        verify(ack).acknowledge();
    }

    @Test
    void aFailureDuringHandlingPropagatesInsteadOfBeingSwallowed() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentInitiatedEvent event = new PaymentInitiatedEvent(
                paymentId, UUID.randomUUID(), UUID.randomUUID(), 5_000L, "USD", PaymentMethod.NETBANKING, Instant.now());
        when(paymentViewRepository.save(any())).thenThrow(new RuntimeException("transient DB blip"));

        assertThatThrownBy(() -> listener.onEvent(recordFor(paymentId, "PAYMENT_INITIATED", event), ack))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("transient DB blip");

        verify(ack, never()).acknowledge();
    }

    // --- helpers -------------------------------------------------------

    private PaymentView existingView(UUID paymentId, PaymentState state) {
        return new PaymentView(paymentId, UUID.randomUUID(), UUID.randomUUID(), 5_000L, "USD", "NETBANKING", state, Instant.now());
    }

    private LedgerPostedEvent holdEvent(UUID paymentId) {
        return new LedgerPostedEvent(UUID.randomUUID(), paymentId, UUID.randomUUID(), UUID.randomUUID(),
                5_000L, "HOLD", Instant.now());
    }

    private <T> ConsumerRecord<String, String> recordFor(UUID paymentId, String eventType, T payload) throws Exception {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), paymentId, eventType, Instant.now(), objectMapper.valueToTree(payload));
        return new ConsumerRecord<>("payment.events", 0, 0L, paymentId.toString(),
                objectMapper.writeValueAsString(envelope));
    }
}
