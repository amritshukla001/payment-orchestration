package com.payflow.paymentapi.api;

import com.payflow.common.enums.PaymentMethod;
import com.payflow.common.enums.PaymentState;
import com.payflow.paymentapi.domain.Payment;
import com.payflow.paymentapi.repository.OutboxEventRepository;
import com.payflow.paymentapi.service.InvalidCardTokenException;
import com.payflow.paymentapi.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PaymentService paymentService;

    @MockBean
    private OutboxEventRepository outboxEventRepository;

    @Test
    void initiateReturns202OnSuccess() throws Exception {
        UUID paymentId = UUID.randomUUID();
        Payment payment = new Payment(paymentId, "key-1", UUID.randomUUID(), UUID.randomUUID(),
                2_500L, "USD", PaymentMethod.NETBANKING, PaymentState.INITIATED, Instant.now(), null);
        when(paymentService.initiate(anyString(), any())).thenReturn(payment);

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-1")
                        .contentType("application/json")
                        .content("""
                                {"payerAccount":"%s","payeeAccount":"%s","amountCents":2500,"currency":"USD","paymentMethod":"NETBANKING"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(paymentId.toString()))
                .andExpect(jsonPath("$.state").value("INITIATED"));
    }

    @Test
    void initiateReturns400WhenTheCardTokenIsInvalid() throws Exception {
        when(paymentService.initiate(anyString(), any()))
                .thenThrow(new InvalidCardTokenException("Stripe rejected this card token"));

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-2")
                        .contentType("application/json")
                        .content("""
                                {"payerAccount":"%s","payeeAccount":"%s","amountCents":2500,"currency":"USD","paymentMethod":"CARD","cardToken":"pm_bad"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }
}
