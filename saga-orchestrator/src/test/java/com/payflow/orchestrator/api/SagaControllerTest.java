package com.payflow.orchestrator.api;

import com.payflow.common.enums.PaymentState;
import com.payflow.orchestrator.domain.PaymentSagaAggregate;
import com.payflow.orchestrator.domain.SagaEventStore;
import com.payflow.orchestrator.domain.SagaSummary;
import com.payflow.orchestrator.summary.CompensationSummaryService;
import com.payflow.orchestrator.summary.SummaryUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SagaController.class)
class SagaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SagaEventStore sagaEventStore;

    @MockBean
    private CompensationSummaryService compensationSummaryService;

    @Test
    void listReturnsSagasMostRecentlyUpdatedFirst() throws Exception {
        PaymentSagaAggregate saga = new PaymentSagaAggregate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                5_000L, "USD", PaymentState.SETTLED, Instant.now());
        when(sagaEventStore.loadAll()).thenReturn(List.of(saga));

        mockMvc.perform(get("/api/sagas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value(saga.getPaymentId().toString()))
                .andExpect(jsonPath("$[0].state").value("SETTLED"));
    }

    @Test
    void getReturnsTheSagaForAKnownPayment() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSagaAggregate saga = new PaymentSagaAggregate(
                paymentId, UUID.randomUUID(), UUID.randomUUID(),
                1_000L, "USD", PaymentState.COMPENSATED, Instant.now());
        when(sagaEventStore.load(paymentId)).thenReturn(Optional.of(saga));

        mockMvc.perform(get("/api/sagas/{id}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPENSATED"));
    }

    @Test
    void getReturns404ForAnUnknownPayment() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(sagaEventStore.load(paymentId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/sagas/{id}", paymentId))
                .andExpect(status().isNotFound());
    }

    @Test
    void summaryReturnsTheGeneratedSummaryWithItsSource() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(compensationSummaryService.summarize(paymentId)).thenReturn(new SagaSummary(
                paymentId, "The payment was reversed.", SagaSummary.Source.AI, Instant.now()));

        mockMvc.perform(get("/api/sagas/{id}/summary", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.summary").value("The payment was reversed."))
                .andExpect(jsonPath("$.source").value("AI"));
    }

    @Test
    void summaryReturns404ForAnUnknownPayment() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(compensationSummaryService.summarize(paymentId))
                .thenThrow(new SummaryUnavailableException(paymentId, "No saga found", true));

        mockMvc.perform(get("/api/sagas/{id}/summary", paymentId))
                .andExpect(status().isNotFound());
    }

    @Test
    void summaryReturns409ForANonCompensatedPayment() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(compensationSummaryService.summarize(paymentId))
                .thenThrow(new SummaryUnavailableException(paymentId, "Payment is SETTLED", false));

        mockMvc.perform(get("/api/sagas/{id}/summary", paymentId))
                .andExpect(status().isConflict());
    }
}
