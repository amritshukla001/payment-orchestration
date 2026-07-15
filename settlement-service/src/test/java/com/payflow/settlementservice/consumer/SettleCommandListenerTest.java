package com.payflow.settlementservice.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.payflow.common.commands.SettleCommand;
import com.payflow.common.events.EventEnvelope;
import com.payflow.common.events.PaymentSettledEvent;
import com.payflow.common.events.SettlementDeclinedEvent;
import com.payflow.settlementservice.domain.Settlement;
import com.payflow.settlementservice.repository.ProcessedEventRepository;
import com.payflow.settlementservice.repository.SettlementRepository;
import com.payflow.settlementservice.risk.SettlementRiskCheck;
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

@ExtendWith(MockitoExtension.class)
class SettleCommandListenerTest {

    @Mock
    private SettlementRepository settlementRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private SettlementRiskCheck riskCheck;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private Acknowledgment ack;

    private ObjectMapper objectMapper;
    private SettleCommandListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new SettleCommandListener(settlementRepository, processedEventRepository, riskCheck, kafkaTemplate, objectMapper);
        lenient().when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(riskCheck.checkForDecline(anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void recordsCaptureAndPublishesPaymentSettled() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID payerAccount = UUID.randomUUID();
        UUID payeeAccount = UUID.randomUUID();
        SettleCommand command = new SettleCommand(
                paymentId, payerAccount, payeeAccount, 4_500L, "USD", Instant.now());
        when(settlementRepository.existsById(paymentId)).thenReturn(false);

        listener.onCommand(recordFor(paymentId, command), ack);

        ArgumentCaptor<Settlement> captor = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementRepository).save(captor.capture());
        assertThat(captor.getValue().getPaymentId()).isEqualTo(paymentId);
        assertThat(captor.getValue().getAmountCents()).isEqualTo(4_500L);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("payment.events"), eq(paymentId.toString()), jsonCaptor.capture());
        EventEnvelope published = objectMapper.readValue(jsonCaptor.getValue(), EventEnvelope.class);
        assertThat(published.eventType()).isEqualTo("PAYMENT_SETTLED");
        PaymentSettledEvent event = objectMapper.treeToValue(published.payload(), PaymentSettledEvent.class);
        assertThat(event.paymentId()).isEqualTo(paymentId);
        assertThat(event.payerAccount()).isEqualTo(payerAccount);
        assertThat(event.payeeAccount()).isEqualTo(payeeAccount);

        verify(ack).acknowledge();
    }

    @Test
    void isIdempotentAgainstARedeliveredCommand() throws Exception {
        UUID paymentId = UUID.randomUUID();
        SettleCommand command = new SettleCommand(
                paymentId, UUID.randomUUID(), UUID.randomUUID(), 4_500L, "USD", Instant.now());
        when(settlementRepository.existsById(paymentId)).thenReturn(true);

        listener.onCommand(recordFor(paymentId, command), ack);

        verify(settlementRepository, never()).save(any());
        // Still publishes PAYMENT_SETTLED -- the outcome is the same regardless of whether
        // this is the first or a redelivered attempt.
        verify(kafkaTemplate).send(eq("payment.events"), eq(paymentId.toString()), anyString());
    }

    @Test
    void publishesSettlementDeclinedInsteadOfCapturingWhenRiskCheckObjects() throws Exception {
        UUID paymentId = UUID.randomUUID();
        SettleCommand command = new SettleCommand(
                paymentId, UUID.randomUUID(), UUID.randomUUID(), 950_000L, "USD", Instant.now());
        when(riskCheck.checkForDecline(950_000L)).thenReturn(Optional.of("Issuer declined capture"));

        listener.onCommand(recordFor(paymentId, command), ack);

        verify(settlementRepository, never()).save(any());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("payment.events"), eq(paymentId.toString()), jsonCaptor.capture());
        EventEnvelope published = objectMapper.readValue(jsonCaptor.getValue(), EventEnvelope.class);
        assertThat(published.eventType()).isEqualTo("SETTLEMENT_DECLINED");
        SettlementDeclinedEvent event = objectMapper.treeToValue(published.payload(), SettlementDeclinedEvent.class);
        assertThat(event.reason()).isEqualTo("Issuer declined capture");

        verify(ack).acknowledge();
    }

    @Test
    void anAlreadyProcessedCommandIsSkippedEntirely() throws Exception {
        UUID paymentId = UUID.randomUUID();
        SettleCommand command = new SettleCommand(
                paymentId, UUID.randomUUID(), UUID.randomUUID(), 4_500L, "USD", Instant.now());
        ConsumerRecord<String, String> record = recordFor(paymentId, command);
        UUID eventId = objectMapper.readValue(record.value(), EventEnvelope.class).eventId();
        when(processedEventRepository.existsById(eventId)).thenReturn(true);

        listener.onCommand(record, ack);

        verifyNoInteractions(settlementRepository);
        verifyNoInteractions(kafkaTemplate);
        verify(ack).acknowledge();
    }

    private ConsumerRecord<String, String> recordFor(UUID paymentId, SettleCommand command) throws Exception {
        JsonNode payload = objectMapper.valueToTree(command);
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), paymentId, "SETTLE", Instant.now(), payload);
        return new ConsumerRecord<>("payment.commands", 0, 0L, paymentId.toString(),
                objectMapper.writeValueAsString(envelope));
    }
}
