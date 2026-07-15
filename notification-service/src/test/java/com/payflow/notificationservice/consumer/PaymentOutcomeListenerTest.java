package com.payflow.notificationservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.payflow.common.events.EventEnvelope;
import com.payflow.common.events.PaymentCompensatedEvent;
import com.payflow.common.events.PaymentFailedEvent;
import com.payflow.common.events.PaymentSettledEvent;
import com.payflow.notificationservice.domain.NotificationRecord;
import com.payflow.notificationservice.domain.Outcome;
import com.payflow.notificationservice.domain.Recipient;
import com.payflow.notificationservice.repository.NotificationRecordRepository;
import com.payflow.notificationservice.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentOutcomeListenerTest {

    @Mock
    private NotificationRecordRepository notificationRecordRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private Acknowledgment ack;

    private ObjectMapper objectMapper;
    private PaymentOutcomeListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new PaymentOutcomeListener(notificationRecordRepository, processedEventRepository, objectMapper);
    }

    @Test
    void paymentSettledNotifiesBothPayerAndPayee() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID payerAccount = UUID.randomUUID();
        UUID payeeAccount = UUID.randomUUID();
        PaymentSettledEvent event = new PaymentSettledEvent(paymentId, payerAccount, payeeAccount, Instant.now());

        listener.onEvent(recordFor(paymentId, "PAYMENT_SETTLED", event), ack);

        ArgumentCaptor<NotificationRecord> captor = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRecordRepository, times(2)).save(captor.capture());
        List<NotificationRecord> saved = captor.getAllValues();

        assertThat(saved).extracting(NotificationRecord::getRecipient)
                .containsExactlyInAnyOrder(Recipient.PAYER, Recipient.PAYEE);
        assertThat(saved).allMatch(n -> n.getOutcome() == Outcome.SUCCESS);
        assertThat(saved).extracting(NotificationRecord::getAccountId)
                .containsExactlyInAnyOrder(payerAccount, payeeAccount);

        verify(ack).acknowledge();
    }

    @Test
    void paymentFailedNotifiesOnlyThePayer() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID payerAccount = UUID.randomUUID();
        PaymentFailedEvent event = new PaymentFailedEvent(paymentId, payerAccount, "over threshold", Instant.now());

        listener.onEvent(recordFor(paymentId, "PAYMENT_FAILED", event), ack);

        ArgumentCaptor<NotificationRecord> captor = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRecordRepository, times(1)).save(captor.capture());
        NotificationRecord saved = captor.getValue();

        assertThat(saved.getRecipient()).isEqualTo(Recipient.PAYER);
        assertThat(saved.getOutcome()).isEqualTo(Outcome.FAILURE);
        assertThat(saved.getAccountId()).isEqualTo(payerAccount);
        assertThat(saved.getMessage()).contains("over threshold");
    }

    @Test
    void paymentCompensatedNotifiesOnlyThePayer() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID payerAccount = UUID.randomUUID();
        PaymentCompensatedEvent event = new PaymentCompensatedEvent(paymentId, payerAccount, Instant.now());

        listener.onEvent(recordFor(paymentId, "PAYMENT_COMPENSATED", event), ack);

        ArgumentCaptor<NotificationRecord> captor = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRecordRepository, times(1)).save(captor.capture());
        NotificationRecord saved = captor.getValue();

        assertThat(saved.getRecipient()).isEqualTo(Recipient.PAYER);
        assertThat(saved.getOutcome()).isEqualTo(Outcome.REVERSED);
        assertThat(saved.getAccountId()).isEqualTo(payerAccount);
        assertThat(saved.getMessage()).contains("reversed");
    }

    @Test
    void ignoresEventTypesItDoesNotCareAbout() throws Exception {
        UUID paymentId = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), paymentId, "FRAUD_APPROVED",
                Instant.now(), objectMapper.valueToTree(Map.of("noop", "payload")));
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment.events", 0, 0L,
                paymentId.toString(), objectMapper.writeValueAsString(envelope));

        listener.onEvent(record, ack);

        verifyNoInteractions(notificationRecordRepository);
        verify(ack).acknowledge();
    }

    @Test
    void anAlreadyProcessedEventIsSkippedEntirely() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSettledEvent event = new PaymentSettledEvent(paymentId, UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        ConsumerRecord<String, String> record = recordFor(paymentId, "PAYMENT_SETTLED", event);
        UUID eventId = objectMapper.readValue(record.value(), EventEnvelope.class).eventId();
        when(processedEventRepository.existsById(eventId)).thenReturn(true);

        listener.onEvent(record, ack);

        verifyNoInteractions(notificationRecordRepository);
        verify(ack).acknowledge();
    }

    private <T> ConsumerRecord<String, String> recordFor(UUID paymentId, String eventType, T payload) throws Exception {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), paymentId, eventType, Instant.now(), objectMapper.valueToTree(payload));
        return new ConsumerRecord<>("payment.events", 0, 0L, paymentId.toString(),
                objectMapper.writeValueAsString(envelope));
    }
}
