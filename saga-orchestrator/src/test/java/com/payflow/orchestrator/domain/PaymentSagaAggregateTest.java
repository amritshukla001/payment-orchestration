package com.payflow.orchestrator.domain;

import com.payflow.common.enums.PaymentState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure fold logic, no mocks -- proves replay() correctly reconstructs an
 * aggregate's current state from an ordered event log, independent of how
 * that log was persisted or queried.
 */
class PaymentSagaAggregateTest {

    @Test
    void replayingJustTheInitiatedEventYieldsInitiatedState() {
        UUID paymentId = UUID.randomUUID();
        UUID payerAccount = UUID.randomUUID();
        UUID payeeAccount = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        SagaEvent initiated = new SagaEvent(UUID.randomUUID(), paymentId, 0, "PAYMENT_INITIATED",
                "INITIATED", payerAccount, payeeAccount, 5_000L, "USD", occurredAt);

        PaymentSagaAggregate aggregate = PaymentSagaAggregate.replay(List.of(initiated));

        assertThat(aggregate.getPaymentId()).isEqualTo(paymentId);
        assertThat(aggregate.getPayerAccount()).isEqualTo(payerAccount);
        assertThat(aggregate.getPayeeAccount()).isEqualTo(payeeAccount);
        assertThat(aggregate.getAmountCents()).isEqualTo(5_000L);
        assertThat(aggregate.getCurrency()).isEqualTo("USD");
        assertThat(aggregate.getState()).isEqualTo(PaymentState.INITIATED);
        assertThat(aggregate.getUpdatedAt()).isEqualTo(occurredAt);
        assertThat(aggregate.nextSequenceNumber()).isEqualTo(1);
    }

    @Test
    void replayingTheFullHappyPathSequenceYieldsSettledAndCarriesPayerPayeeForward() {
        UUID paymentId = UUID.randomUUID();
        UUID payerAccount = UUID.randomUUID();
        UUID payeeAccount = UUID.randomUUID();
        Instant t0 = Instant.now();

        List<SagaEvent> events = List.of(
                new SagaEvent(UUID.randomUUID(), paymentId, 0, "PAYMENT_INITIATED", "INITIATED",
                        payerAccount, payeeAccount, 5_000L, "USD", t0),
                new SagaEvent(UUID.randomUUID(), paymentId, 1, "FRAUD_APPROVED", "FRAUD_CHECKED",
                        null, null, null, null, t0.plusSeconds(1)),
                new SagaEvent(UUID.randomUUID(), paymentId, 2, "FUNDS_AUTHORIZED", "AUTHORIZED",
                        null, null, null, null, t0.plusSeconds(2)),
                new SagaEvent(UUID.randomUUID(), paymentId, 3, "LEDGER_POSTED", "LEDGER_POSTED",
                        null, null, null, null, t0.plusSeconds(3)),
                new SagaEvent(UUID.randomUUID(), paymentId, 4, "PAYMENT_SETTLED", "SETTLED",
                        null, null, null, null, t0.plusSeconds(4))
        );

        PaymentSagaAggregate aggregate = PaymentSagaAggregate.replay(events);

        assertThat(aggregate.getState()).isEqualTo(PaymentState.SETTLED);
        assertThat(aggregate.getUpdatedAt()).isEqualTo(t0.plusSeconds(4));
        // Payer/payee/amount/currency only ever appear on the INITIATED row,
        // yet the folded aggregate still carries them correctly.
        assertThat(aggregate.getPayerAccount()).isEqualTo(payerAccount);
        assertThat(aggregate.getPayeeAccount()).isEqualTo(payeeAccount);
        assertThat(aggregate.getAmountCents()).isEqualTo(5_000L);
        assertThat(aggregate.getCurrency()).isEqualTo("USD");
        assertThat(aggregate.nextSequenceNumber()).isEqualTo(5);
    }

    @Test
    void replayingTheCompensationSequenceEndsInCompensated() {
        UUID paymentId = UUID.randomUUID();
        Instant t0 = Instant.now();

        List<SagaEvent> events = List.of(
                new SagaEvent(UUID.randomUUID(), paymentId, 0, "PAYMENT_INITIATED", "INITIATED",
                        UUID.randomUUID(), UUID.randomUUID(), 950_000L, "USD", t0),
                new SagaEvent(UUID.randomUUID(), paymentId, 1, "FRAUD_APPROVED", "FRAUD_CHECKED",
                        null, null, null, null, t0.plusSeconds(1)),
                new SagaEvent(UUID.randomUUID(), paymentId, 2, "FUNDS_AUTHORIZED", "AUTHORIZED",
                        null, null, null, null, t0.plusSeconds(2)),
                new SagaEvent(UUID.randomUUID(), paymentId, 3, "LEDGER_POSTED", "LEDGER_POSTED",
                        null, null, null, null, t0.plusSeconds(3)),
                new SagaEvent(UUID.randomUUID(), paymentId, 4, "SETTLEMENT_DECLINED", "COMPENSATING",
                        null, null, null, null, t0.plusSeconds(4)),
                new SagaEvent(UUID.randomUUID(), paymentId, 5, "FUNDS_RELEASED", "COMPENSATED",
                        null, null, null, null, t0.plusSeconds(5))
        );

        PaymentSagaAggregate aggregate = PaymentSagaAggregate.replay(events);

        assertThat(aggregate.getState()).isEqualTo(PaymentState.COMPENSATED);
    }
}
