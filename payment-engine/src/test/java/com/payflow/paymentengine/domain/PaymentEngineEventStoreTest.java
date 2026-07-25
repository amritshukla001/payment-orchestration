package com.payflow.paymentengine.domain;

import com.payflow.common.enums.PaymentState;
import com.payflow.paymentengine.repository.PaymentEngineEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentEngineEventStoreTest {

    @Mock
    private PaymentEngineEventRepository repository;

    private PaymentEngineEventStore store() {
        return new PaymentEngineEventStore(repository);
    }

    @Test
    void loadReturnsEmptyWhenNoEventsExistForThePayment() {
        UUID paymentId = UUID.randomUUID();
        when(repository.findByPaymentIdOrderBySequenceNumberAsc(paymentId)).thenReturn(List.of());

        assertThat(store().load(paymentId)).isEmpty();
    }

    @Test
    void loadFoldsTheStoredEventsIntoAnAggregate() {
        UUID paymentId = UUID.randomUUID();
        PaymentEngineEvent initiated = new PaymentEngineEvent(UUID.randomUUID(), paymentId, 0, "PAYMENT_INITIATED",
                "INITIATED", UUID.randomUUID(), UUID.randomUUID(), 5_000L, "USD", "NETBANKING", Instant.now());
        when(repository.findByPaymentIdOrderBySequenceNumberAsc(paymentId)).thenReturn(List.of(initiated));

        Optional<PaymentEngineAggregate> loaded = store().load(paymentId);

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getState()).isEqualTo(PaymentState.INITIATED);
    }

    @Test
    void appendInitiatedWritesSequenceZero() {
        UUID paymentId = UUID.randomUUID();
        UUID payerAccount = UUID.randomUUID();
        UUID payeeAccount = UUID.randomUUID();
        Instant now = Instant.now();

        store().appendInitiated(paymentId, payerAccount, payeeAccount, 5_000L, "USD", "NETBANKING", now);

        ArgumentCaptor<PaymentEngineEvent> captor = ArgumentCaptor.forClass(PaymentEngineEvent.class);
        verify(repository).save(captor.capture());
        PaymentEngineEvent saved = captor.getValue();
        assertThat(saved.getPaymentId()).isEqualTo(paymentId);
        assertThat(saved.getSequenceNumber()).isEqualTo(0);
        assertThat(saved.getEventType()).isEqualTo("PAYMENT_INITIATED");
        assertThat(saved.getToState()).isEqualTo("INITIATED");
        assertThat(saved.getPayerAccount()).isEqualTo(payerAccount);
        assertThat(saved.getPayeeAccount()).isEqualTo(payeeAccount);
        assertThat(saved.getAmountCents()).isEqualTo(5_000L);
        assertThat(saved.getCurrency()).isEqualTo("USD");
        assertThat(saved.getPaymentMethod()).isEqualTo("NETBANKING");
    }

    @Test
    void appendWritesTheAggregatesNextSequenceNumberWithNoPayloadFields() {
        UUID paymentId = UUID.randomUUID();
        PaymentEngineEvent initiated = new PaymentEngineEvent(UUID.randomUUID(), paymentId, 0, "PAYMENT_INITIATED",
                "INITIATED", UUID.randomUUID(), UUID.randomUUID(), 5_000L, "USD", "NETBANKING", Instant.now());
        PaymentEngineAggregate aggregate = PaymentEngineAggregate.replay(List.of(initiated));

        store().append(aggregate, "FRAUD_APPROVED", PaymentState.FRAUD_CHECKED, Instant.now());

        ArgumentCaptor<PaymentEngineEvent> captor = ArgumentCaptor.forClass(PaymentEngineEvent.class);
        verify(repository).save(captor.capture());
        PaymentEngineEvent saved = captor.getValue();
        assertThat(saved.getSequenceNumber()).isEqualTo(1);
        assertThat(saved.getEventType()).isEqualTo("FRAUD_APPROVED");
        assertThat(saved.getToState()).isEqualTo("FRAUD_CHECKED");
        assertThat(saved.getPayerAccount()).isNull();
        assertThat(saved.getPayeeAccount()).isNull();
        assertThat(saved.getAmountCents()).isNull();
        assertThat(saved.getCurrency()).isNull();
    }

    @Test
    void loadAllGroupsEventsPerPaymentAndSortsByUpdatedAtDescending() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        Instant t0 = Instant.now();

        PaymentEngineEvent olderInitiated = new PaymentEngineEvent(UUID.randomUUID(), older, 0, "PAYMENT_INITIATED",
                "INITIATED", UUID.randomUUID(), UUID.randomUUID(), 1_000L, "USD", "NETBANKING", t0);
        PaymentEngineEvent olderAdvanced = new PaymentEngineEvent(UUID.randomUUID(), older, 1, "FRAUD_APPROVED",
                "FRAUD_CHECKED", null, null, null, null, null, t0.plusSeconds(1));
        PaymentEngineEvent newerInitiated = new PaymentEngineEvent(UUID.randomUUID(), newer, 0, "PAYMENT_INITIATED",
                "INITIATED", UUID.randomUUID(), UUID.randomUUID(), 2_000L, "USD", "NETBANKING", t0.plusSeconds(5));

        when(repository.findAllByOrderByPaymentIdAscSequenceNumberAsc())
                .thenReturn(List.of(olderInitiated, olderAdvanced, newerInitiated));

        List<PaymentEngineAggregate> all = store().loadAll();

        assertThat(all).hasSize(2);
        assertThat(all.get(0).getPaymentId()).isEqualTo(newer);
        assertThat(all.get(0).getState()).isEqualTo(PaymentState.INITIATED);
        assertThat(all.get(1).getPaymentId()).isEqualTo(older);
        assertThat(all.get(1).getState()).isEqualTo(PaymentState.FRAUD_CHECKED);
    }
}
