package com.payflow.paymentapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.payflow.common.enums.PaymentMethod;
import com.payflow.paymentapi.api.dto.CreatePaymentRequest;
import com.payflow.paymentapi.domain.OutboxEvent;
import com.payflow.paymentapi.domain.Payment;
import com.payflow.paymentapi.repository.OutboxEventRepository;
import com.payflow.paymentapi.repository.PaymentRepository;
import com.payflow.paymentapi.stripe.StripeCardTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private StripeCardTokenVerifier stripeCardTokenVerifier;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                paymentRepository, outboxEventRepository,
                new ObjectMapper().registerModule(new JavaTimeModule()), stripeCardTokenVerifier);
    }

    @Test
    void nonCardPaymentsNeverCallTheStripeVerifier() {
        when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());

        paymentService.initiate("key-1", requestFor(PaymentMethod.NETBANKING, null));

        verifyNoInteractions(stripeCardTokenVerifier);
        verify(paymentRepository).save(any(Payment.class));
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void cardPaymentsVerifyTheTokenBeforePersistingAnything() {
        when(paymentRepository.findByIdempotencyKey("key-2")).thenReturn(Optional.empty());

        paymentService.initiate("key-2", requestFor(PaymentMethod.CARD, "pm_valid"));

        verify(stripeCardTokenVerifier).verify("pm_valid");
        verify(paymentRepository).save(any(Payment.class));
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void aRejectedCardTokenStopsThePaymentFromBeingPersistedAtAll() {
        when(paymentRepository.findByIdempotencyKey("key-3")).thenReturn(Optional.empty());
        doThrow(new InvalidCardTokenException("nope"))
                .when(stripeCardTokenVerifier).verify("pm_bad");

        assertThatThrownBy(() -> paymentService.initiate("key-3", requestFor(PaymentMethod.CARD, "pm_bad")))
                .isInstanceOf(InvalidCardTokenException.class);

        verify(paymentRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void aRetriedIdempotencyKeyNeverReVerifiesTheCard() {
        Payment existing = new Payment(UUID.randomUUID(), "key-4", UUID.randomUUID(), UUID.randomUUID(),
                2_500L, "USD", PaymentMethod.CARD, com.payflow.common.enums.PaymentState.INITIATED,
                java.time.Instant.now(), "pm_original");
        when(paymentRepository.findByIdempotencyKey("key-4")).thenReturn(Optional.of(existing));

        Payment result = paymentService.initiate("key-4", requestFor(PaymentMethod.CARD, "pm_different"));

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(stripeCardTokenVerifier);
        verify(paymentRepository, never()).save(any());
    }

    private CreatePaymentRequest requestFor(PaymentMethod method, String cardToken) {
        return new CreatePaymentRequest(UUID.randomUUID(), UUID.randomUUID(), 2_500L, "USD", method, cardToken);
    }
}
