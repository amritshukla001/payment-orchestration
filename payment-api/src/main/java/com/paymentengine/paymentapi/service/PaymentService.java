package com.paymentengine.paymentapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentengine.common.enums.PaymentState;
import com.paymentengine.common.events.PaymentEventType;
import com.paymentengine.common.events.PaymentInitiatedEvent;
import com.paymentengine.paymentapi.api.dto.CreatePaymentRequest;
import com.paymentengine.paymentapi.domain.OutboxEvent;
import com.paymentengine.paymentapi.domain.Payment;
import com.paymentengine.paymentapi.repository.OutboxEventRepository;
import com.paymentengine.paymentapi.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentRepository paymentRepository,
                           OutboxEventRepository outboxEventRepository,
                           ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a payment and its outbox event atomically. If the idempotency
     * key has already been used, returns the original payment instead of
     * creating a duplicate — retried client requests are always safe.
     */
    @Transactional
    public Payment initiate(String idempotencyKey, CreatePaymentRequest request) {
        return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> createNew(idempotencyKey, request));
    }

    private Payment createNew(String idempotencyKey, CreatePaymentRequest request) {
        Instant now = Instant.now();
        UUID paymentId = UUID.randomUUID();

        Payment payment = new Payment(
                paymentId,
                idempotencyKey,
                request.payerAccount(),
                request.payeeAccount(),
                request.amountCents(),
                request.currency(),
                request.paymentMethod(),
                PaymentState.INITIATED,
                now
        );
        paymentRepository.save(payment);

        PaymentInitiatedEvent event = new PaymentInitiatedEvent(
                paymentId,
                request.payerAccount(),
                request.payeeAccount(),
                request.amountCents(),
                request.currency(),
                request.paymentMethod(),
                now
        );
        outboxEventRepository.save(new OutboxEvent(
                UUID.randomUUID(),
                paymentId,
                PaymentEventType.PAYMENT_INITIATED.name(),
                writeJson(event),
                now
        ));

        return payment;
    }

    @Transactional(readOnly = true)
    public Payment getById(UUID id) {
        return paymentRepository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event payload", e);
        }
    }
}
