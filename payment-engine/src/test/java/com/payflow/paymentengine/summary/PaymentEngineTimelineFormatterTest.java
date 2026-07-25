package com.payflow.paymentengine.summary;

import com.payflow.paymentengine.domain.PaymentEngineEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentEngineTimelineFormatterTest {

    private final PaymentEngineTimelineFormatter formatter = new PaymentEngineTimelineFormatter();

    @Test
    void rendersHeaderFromTheInitiatedRowAndOneLinePerTransition() {
        UUID paymentId = UUID.randomUUID();
        UUID payerAccount = UUID.randomUUID();
        UUID payeeAccount = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-07-25T10:00:00Z");

        List<PaymentEngineEvent> events = List.of(
                new PaymentEngineEvent(UUID.randomUUID(), paymentId, 0, "PAYMENT_INITIATED", "INITIATED",
                        payerAccount, payeeAccount, 950_000L, "USD", "NETBANKING", t0),
                new PaymentEngineEvent(UUID.randomUUID(), paymentId, 1, "SETTLEMENT_DECLINED", "COMPENSATING",
                        null, null, null, null, null, t0.plusSeconds(2))
        );

        String timeline = formatter.format(events);

        assertThat(timeline).contains("Payment " + paymentId);
        assertThat(timeline).contains("USD 9,500.00");
        assertThat(timeline).contains("Payer account: " + payerAccount);
        assertThat(timeline).contains("Payee account: " + payeeAccount);
        assertThat(timeline).contains("0. PAYMENT_INITIATED -> INITIATED at " + t0);
        assertThat(timeline).contains("1. SETTLEMENT_DECLINED -> COMPENSATING at " + t0.plusSeconds(2));
    }

    @Test
    void formatsAmountsWithCurrencyAndThousandsSeparators() {
        assertThat(formatter.formatAmount(950_000L, "USD")).isEqualTo("USD 9,500.00");
        assertThat(formatter.formatAmount(2_500L, "EUR")).isEqualTo("EUR 25.00");
    }
}
