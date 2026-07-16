package com.payflow.orchestrator.api;

import com.payflow.common.enums.PaymentState;
import com.payflow.orchestrator.domain.PaymentSagaState;
import com.payflow.orchestrator.repository.PaymentSagaStateRepository;
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
    private PaymentSagaStateRepository repository;

    @Test
    void listReturnsSagasMostRecentlyUpdatedFirst() throws Exception {
        PaymentSagaState saga = new PaymentSagaState(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                5_000L, "USD", PaymentState.SETTLED, Instant.now());
        when(repository.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(saga));

        mockMvc.perform(get("/api/sagas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value(saga.getPaymentId().toString()))
                .andExpect(jsonPath("$[0].state").value("SETTLED"));
    }

    @Test
    void getReturnsTheSagaForAKnownPayment() throws Exception {
        UUID paymentId = UUID.randomUUID();
        PaymentSagaState saga = new PaymentSagaState(
                paymentId, UUID.randomUUID(), UUID.randomUUID(),
                1_000L, "USD", PaymentState.COMPENSATED, Instant.now());
        when(repository.findById(paymentId)).thenReturn(Optional.of(saga));

        mockMvc.perform(get("/api/sagas/{id}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPENSATED"));
    }

    @Test
    void getReturns404ForAnUnknownPayment() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(repository.findById(paymentId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/sagas/{id}", paymentId))
                .andExpect(status().isNotFound());
    }
}
