package com.payflow.paymentapi.api;

import com.payflow.paymentapi.api.dto.CreatePaymentRequest;
import com.payflow.paymentapi.api.dto.PaymentResponse;
import com.payflow.paymentapi.api.dto.TimelineEntryResponse;
import com.payflow.paymentapi.domain.Payment;
import com.payflow.paymentapi.repository.OutboxEventRepository;
import com.payflow.paymentapi.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final OutboxEventRepository outboxEventRepository;

    public PaymentController(PaymentService paymentService, OutboxEventRepository outboxEventRepository) {
        this.paymentService = paymentService;
        this.outboxEventRepository = outboxEventRepository;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> initiate(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        Payment payment = paymentService.initiate(idempotencyKey, request);
        return ResponseEntity
                .accepted()
                .location(URI.create("/payments/" + payment.getId()))
                .body(PaymentResponse.from(payment));
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.from(paymentService.getById(id));
    }

    @GetMapping("/{id}/timeline")
    public List<TimelineEntryResponse> timeline(@PathVariable UUID id) {
        return outboxEventRepository.findByAggregateIdOrderByCreatedAtAsc(id).stream()
                .map(TimelineEntryResponse::from)
                .toList();
    }

    @ExceptionHandler(com.payflow.paymentapi.service.PaymentNotFoundException.class)
    public ResponseEntity<String> handleNotFound(com.payflow.paymentapi.service.PaymentNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }
}
