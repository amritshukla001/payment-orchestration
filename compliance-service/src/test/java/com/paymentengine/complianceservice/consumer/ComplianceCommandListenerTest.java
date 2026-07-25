package com.paymentengine.complianceservice.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.paymentengine.common.commands.CheckComplianceCommand;
import com.paymentengine.common.enums.PaymentMethod;
import com.paymentengine.common.events.ComplianceApprovedEvent;
import com.paymentengine.common.events.ComplianceRejectedEvent;
import com.paymentengine.common.events.EventEnvelope;
import com.paymentengine.complianceservice.domain.RegulatoryReport;
import com.paymentengine.complianceservice.repository.ProcessedEventRepository;
import com.paymentengine.complianceservice.repository.RegulatoryReportRepository;
import com.paymentengine.complianceservice.rules.ComplianceRuleEngine;
import com.paymentengine.complianceservice.rules.Verdict;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mirrors the shape every other command listener in this codebase is held
 * to (idempotency, both verdict outcomes, failure propagation) plus the
 * one thing unique to this listener: the AML regulatory report is a side
 * effect recorded regardless of the eventual verdict.
 */
@ExtendWith(MockitoExtension.class)
class ComplianceCommandListenerTest {

    private static final long AML_THRESHOLD_CENTS = 1_000_000L;

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private RegulatoryReportRepository regulatoryReportRepository;
    @Mock
    private ComplianceRuleEngine ruleEngine;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private Acknowledgment ack;

    private ObjectMapper objectMapper;
    private ComplianceCommandListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new ComplianceCommandListener(processedEventRepository, regulatoryReportRepository,
                ruleEngine, kafkaTemplate, objectMapper, AML_THRESHOLD_CENTS);
        lenient().when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void anApprovedVerdictPublishesComplianceApproved() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(ruleEngine.evaluate(any())).thenReturn(Verdict.approve());

        listener.onCommand(recordFor(paymentId, 5_000L), ack);

        ComplianceApprovedEvent event = capturedEvent(paymentId, "COMPLIANCE_APPROVED", ComplianceApprovedEvent.class);
        assertThat(event.paymentId()).isEqualTo(paymentId);
        verify(regulatoryReportRepository, never()).save(any());
        verify(ack).acknowledge();
    }

    @Test
    void aRejectedVerdictPublishesComplianceRejectedWithTheReason() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(ruleEngine.evaluate(any())).thenReturn(Verdict.reject("Payer account is not KYC-verified"));

        listener.onCommand(recordFor(paymentId, 5_000L), ack);

        ComplianceRejectedEvent event = capturedEvent(paymentId, "COMPLIANCE_REJECTED", ComplianceRejectedEvent.class);
        assertThat(event.reason()).isEqualTo("Payer account is not KYC-verified");
    }

    @Test
    void anAmountAtOrAboveTheAmlThresholdIsAlwaysReportedRegardlessOfVerdict() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(ruleEngine.evaluate(any())).thenReturn(Verdict.reject("Payer account is not KYC-verified"));

        listener.onCommand(recordFor(paymentId, AML_THRESHOLD_CENTS), ack);

        ArgumentCaptor<RegulatoryReport> captor = ArgumentCaptor.forClass(RegulatoryReport.class);
        verify(regulatoryReportRepository).save(captor.capture());
        assertThat(captor.getValue().getPaymentId()).isEqualTo(paymentId);
        assertThat(captor.getValue().getAmountCents()).isEqualTo(AML_THRESHOLD_CENTS);
    }

    @Test
    void anAmountBelowTheAmlThresholdIsNotReported() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(ruleEngine.evaluate(any())).thenReturn(Verdict.approve());

        listener.onCommand(recordFor(paymentId, AML_THRESHOLD_CENTS - 1), ack);

        verify(regulatoryReportRepository, never()).save(any());
    }

    @Test
    void anAlreadyProcessedCommandIsSkippedEntirely() throws Exception {
        UUID paymentId = UUID.randomUUID();
        ConsumerRecord<String, String> record = recordFor(paymentId, 5_000L);
        UUID eventId = objectMapper.readValue(record.value(), EventEnvelope.class).eventId();
        when(processedEventRepository.existsById(eventId)).thenReturn(true);

        listener.onCommand(record, ack);

        verifyNoInteractions(ruleEngine);
        verifyNoInteractions(kafkaTemplate);
        verify(ack).acknowledge();
    }

    @Test
    void aCommandOfADifferentTypeIsIgnored() throws Exception {
        UUID paymentId = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), paymentId, "CHECK_FRAUD",
                Instant.now(), objectMapper.valueToTree(Map.of()));
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.commands", 0, 0L,
                paymentId.toString(), objectMapper.writeValueAsString(envelope));

        listener.onCommand(record, ack);

        verifyNoInteractions(ruleEngine);
        verifyNoInteractions(processedEventRepository);
        verify(ack).acknowledge();
    }

    @Test
    void aFailureDuringHandlingPropagatesInsteadOfBeingSwallowed() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(ruleEngine.evaluate(any())).thenThrow(new RuntimeException("transient DB blip"));

        assertThatThrownBy(() -> listener.onCommand(recordFor(paymentId, 5_000L), ack))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("transient DB blip");

        verify(ack, never()).acknowledge();
    }

    private ConsumerRecord<String, String> recordFor(UUID paymentId, long amountCents) throws Exception {
        CheckComplianceCommand command = new CheckComplianceCommand(paymentId, UUID.randomUUID(), UUID.randomUUID(),
                amountCents, "USD", PaymentMethod.NETBANKING, Instant.now());
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), paymentId, "CHECK_COMPLIANCE", Instant.now(), objectMapper.valueToTree(command));
        return new ConsumerRecord<>("payment.commands", 0, 0L, paymentId.toString(),
                objectMapper.writeValueAsString(envelope));
    }

    private <T> T capturedEvent(UUID paymentId, String expectedType, Class<T> payloadType) throws Exception {
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("payment.events"), eq(paymentId.toString()), jsonCaptor.capture());
        EventEnvelope envelope = objectMapper.readValue(jsonCaptor.getValue(), EventEnvelope.class);
        assertThat(envelope.eventType()).isEqualTo(expectedType);
        JsonNode payload = envelope.payload();
        return objectMapper.treeToValue(payload, payloadType);
    }
}
