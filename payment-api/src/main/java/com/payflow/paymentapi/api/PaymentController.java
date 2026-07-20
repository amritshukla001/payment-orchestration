package com.payflow.paymentapi.api;

import com.payflow.paymentapi.api.dto.CreatePaymentRequest;
import com.payflow.paymentapi.api.dto.PaymentResponse;
import com.payflow.paymentapi.api.dto.TimelineEntryResponse;
import com.payflow.paymentapi.domain.Payment;
import com.payflow.paymentapi.repository.OutboxEventRepository;
import com.payflow.paymentapi.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
@Tag(name = "Payments", description = "Kicks off a payment saga and reports on its current state")
public class PaymentController {

    private final PaymentService paymentService;
    private final OutboxEventRepository outboxEventRepository;

    public PaymentController(PaymentService paymentService, OutboxEventRepository outboxEventRepository) {
        this.paymentService = paymentService;
        this.outboxEventRepository = outboxEventRepository;
    }

    @PostMapping
    @Operation(summary = "Initiate a payment",
            description = "Persists the payment and publishes an INITIATED event via the transactional "
                    + "outbox, kicking off the saga. Idempotent on Idempotency-Key: a retried request with "
                    + "the same key returns the original payment instead of creating a duplicate.")
    public ResponseEntity<PaymentResponse> initiate(
            @Parameter(description = "Client-generated key; retries with the same key are safe")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        Payment payment = paymentService.initiate(idempotencyKey, request);
        return ResponseEntity
                .accepted()
                .location(URI.create("/payments/" + payment.getId()))
                .body(PaymentResponse.from(payment));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a payment",
            description = "Returns payment-api's own record, stamped INITIATED at creation time. For the "
                    + "saga's live, current state see GET /api/sagas/{paymentId} on saga-orchestrator.")
    public PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.from(paymentService.getById(id));
    }

    @GetMapping("/{id}/timeline")
    @Operation(summary = "Get a payment's outbox event timeline",
            description = "The ordered sequence of events payment-api's transactional outbox has "
                    + "published for this payment.")
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
