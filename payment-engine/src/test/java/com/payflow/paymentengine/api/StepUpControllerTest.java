package com.payflow.paymentengine.api;

import com.payflow.common.enums.PaymentState;
import com.payflow.paymentengine.domain.PaymentEngineAggregate;
import com.payflow.paymentengine.domain.PaymentEngineEventStore;
import com.payflow.paymentengine.domain.PaymentEngineTransitions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StepUpController.class)
class StepUpControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentEngineEventStore paymentEngineEventStore;

    @MockBean
    private PaymentEngineTransitions transitions;

    @Test
    void confirmResumesAPaymentAwaitingStepUp() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentEngineAggregate aggregate = awaitingStepUp(paymentId);
        when(paymentEngineEventStore.load(paymentId)).thenReturn(Optional.of(aggregate));

        mockMvc.perform(post("/api/payment-engine/{id}/step-up/confirm", paymentId))
                .andExpect(status().isAccepted());

        verify(transitions).resumeAfterStepUp(aggregate);
    }

    @Test
    void declineFailsAPaymentAwaitingStepUp() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentEngineAggregate aggregate = awaitingStepUp(paymentId);
        when(paymentEngineEventStore.load(paymentId)).thenReturn(Optional.of(aggregate));

        mockMvc.perform(post("/api/payment-engine/{id}/step-up/decline", paymentId))
                .andExpect(status().isAccepted());

        verify(transitions).failPayment(paymentId, "STEP_UP_DECLINED", "Card step-up declined by customer", "card step-up declined");
    }

    @Test
    void confirmReturns404ForAnUnknownPayment() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentEngineEventStore.load(paymentId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/payment-engine/{id}/step-up/confirm", paymentId))
                .andExpect(status().isNotFound());

        verifyNoInteractions(transitions);
    }

    @Test
    void confirmReturns409WhenThePaymentIsNotAwaitingStepUp() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentEngineAggregate aggregate = new PaymentEngineAggregate(paymentId, UUID.randomUUID(), UUID.randomUUID(),
                5_000L, "USD", "CARD", PaymentState.SETTLED, Instant.now());
        when(paymentEngineEventStore.load(paymentId)).thenReturn(Optional.of(aggregate));

        mockMvc.perform(post("/api/payment-engine/{id}/step-up/confirm", paymentId))
                .andExpect(status().isConflict());

        verifyNoInteractions(transitions);
    }

    private PaymentEngineAggregate awaitingStepUp(UUID paymentId) {
        return new PaymentEngineAggregate(paymentId, UUID.randomUUID(), UUID.randomUUID(),
                5_000L, "USD", "CARD", PaymentState.AWAITING_STEP_UP, Instant.now());
    }
}
