package com.payflow.paymentengine.api;

import com.payflow.common.enums.PaymentState;
import com.payflow.paymentengine.domain.PaymentEngineAggregate;
import com.payflow.paymentengine.domain.PaymentEngineEventStore;
import com.payflow.paymentengine.domain.PaymentEngineSummary;
import com.payflow.paymentengine.summary.CompensationSummaryService;
import com.payflow.paymentengine.summary.SummaryUnavailableException;
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

@WebMvcTest(PaymentEngineController.class)
class PaymentEngineControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentEngineEventStore paymentEngineEventStore;

    @MockBean
    private CompensationSummaryService compensationSummaryService;

    @Test
    void listReturnsPaymentsMostRecentlyUpdatedFirst() throws Exception {
        PaymentEngineAggregate aggregate = new PaymentEngineAggregate(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                5_000L, "USD", "NETBANKING", PaymentState.SETTLED, Instant.now());
        when(paymentEngineEventStore.loadAll()).thenReturn(List.of(aggregate));

        mockMvc.perform(get("/api/payment-engine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value(aggregate.getPaymentId().toString()))
                .andExpect(jsonPath("$[0].state").value("SETTLED"));
    }

    @Test
    void getReturnsThePaymentForAKnownPayment() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentEngineAggregate aggregate = new PaymentEngineAggregate(
                paymentId, UUID.randomUUID(), UUID.randomUUID(),
                1_000L, "USD", "NETBANKING", PaymentState.COMPENSATED, Instant.now());
        when(paymentEngineEventStore.load(paymentId)).thenReturn(Optional.of(aggregate));

        mockMvc.perform(get("/api/payment-engine/{id}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPENSATED"));
    }

    @Test
    void getReturns404ForAnUnknownPayment() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentEngineEventStore.load(paymentId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/payment-engine/{id}", paymentId))
                .andExpect(status().isNotFound());
    }

    @Test
    void summaryReturnsTheGeneratedSummaryWithItsSource() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(compensationSummaryService.summarize(paymentId)).thenReturn(new PaymentEngineSummary(
                paymentId, "The payment was reversed.", PaymentEngineSummary.Source.AI, Instant.now()));

        mockMvc.perform(get("/api/payment-engine/{id}/summary", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.summary").value("The payment was reversed."))
                .andExpect(jsonPath("$.source").value("AI"));
    }

    @Test
    void summaryReturns404ForAnUnknownPayment() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(compensationSummaryService.summarize(paymentId))
                .thenThrow(new SummaryUnavailableException(paymentId, "No payment found", true));

        mockMvc.perform(get("/api/payment-engine/{id}/summary", paymentId))
                .andExpect(status().isNotFound());
    }

    @Test
    void summaryReturns409ForANonCompensatedPayment() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(compensationSummaryService.summarize(paymentId))
                .thenThrow(new SummaryUnavailableException(paymentId, "Payment is SETTLED", false));

        mockMvc.perform(get("/api/payment-engine/{id}/summary", paymentId))
                .andExpect(status().isConflict());
    }
}
