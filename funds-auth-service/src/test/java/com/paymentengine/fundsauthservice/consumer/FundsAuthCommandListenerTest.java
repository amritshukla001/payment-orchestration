package com.payflow.fundsauthservice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.payflow.common.commands.AuthorizeFundsCommand;
import com.payflow.common.commands.ReleaseFundsCommand;
import com.payflow.common.events.EventEnvelope;
import com.payflow.common.events.FundsAuthorizedEvent;
import com.payflow.common.events.FundsReleasedEvent;
import com.payflow.fundsauthservice.bank.MockBankLedger;
import com.payflow.fundsauthservice.repository.ProcessedEventRepository;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FundsAuthCommandListenerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private MockBankLedger bankLedger;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private Acknowledgment ack;

    private ObjectMapper objectMapper;
    private FundsAuthCommandListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new FundsAuthCommandListener(processedEventRepository, bankLedger, kafkaTemplate, objectMapper);
        lenient().when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void authorizeFundsPublishesFundsAuthorizedWhenApproved() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID payerAccount = UUID.randomUUID();
        AuthorizeFundsCommand command = new AuthorizeFundsCommand(paymentId, payerAccount, 5_000L, "USD", Instant.now());
        when(bankLedger.reserve(paymentId, payerAccount, 5_000L)).thenReturn(MockBankLedger.Result.authorize());

        listener.onCommand(recordFor(paymentId, "AUTHORIZE_FUNDS", command), ack);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("payment.events"), eq(paymentId.toString()), jsonCaptor.capture());
        EventEnvelope published = objectMapper.readValue(jsonCaptor.getValue(), EventEnvelope.class);
        assertThat(published.eventType()).isEqualTo("FUNDS_AUTHORIZED");
        FundsAuthorizedEvent event = objectMapper.treeToValue(published.payload(), FundsAuthorizedEvent.class);
        assertThat(event.paymentId()).isEqualTo(paymentId);
    }

    @Test
    void releaseFundsCallsBankLedgerAndPublishesFundsReleased() throws Exception {
        UUID paymentId = UUID.randomUUID();
        ReleaseFundsCommand command = new ReleaseFundsCommand(paymentId, UUID.randomUUID(), 5_000L, Instant.now());

        listener.onCommand(recordFor(paymentId, "RELEASE_FUNDS", command), ack);

        verify(bankLedger).release(paymentId);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("payment.events"), eq(paymentId.toString()), jsonCaptor.capture());
        EventEnvelope published = objectMapper.readValue(jsonCaptor.getValue(), EventEnvelope.class);
        assertThat(published.eventType()).isEqualTo("FUNDS_RELEASED");
        FundsReleasedEvent event = objectMapper.treeToValue(published.payload(), FundsReleasedEvent.class);
        assertThat(event.paymentId()).isEqualTo(paymentId);
    }

    @Test
    void aFailureDuringHandlingPropagatesInsteadOfBeingSwallowed() throws Exception {
        // See PaymentEventListenerTest's identical test for why.
        UUID paymentId = UUID.randomUUID();
        UUID payerAccount = UUID.randomUUID();
        AuthorizeFundsCommand command = new AuthorizeFundsCommand(paymentId, payerAccount, 5_000L, "USD", Instant.now());
        when(bankLedger.reserve(paymentId, payerAccount, 5_000L)).thenThrow(new RuntimeException("transient bank blip"));

        assertThatThrownBy(() -> listener.onCommand(recordFor(paymentId, "AUTHORIZE_FUNDS", command), ack))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("transient bank blip");

        verify(ack, never()).acknowledge();
    }

    private <T> ConsumerRecord<String, String> recordFor(UUID paymentId, String eventType, T payload) throws Exception {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), paymentId, eventType, Instant.now(), objectMapper.valueToTree(payload));
        return new ConsumerRecord<>("payment.commands", 0, 0L, paymentId.toString(),
                objectMapper.writeValueAsString(envelope));
    }
}
