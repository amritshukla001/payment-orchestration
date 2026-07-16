package com.payflow.orchestrator.api;

import com.payflow.orchestrator.api.dto.SagaResponse;
import com.payflow.orchestrator.repository.PaymentSagaStateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Read-only view onto the saga state machine for the dashboard. This is the
 * only service that knows a payment's live, current state -- payment-api's
 * own Payment row is stamped INITIATED at creation and never updated again,
 * since it has no Kafka consumer of its own.
 */
@RestController
@RequestMapping("/api/sagas")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:4173"})
public class SagaController {

    private final PaymentSagaStateRepository repository;

    public SagaController(PaymentSagaStateRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<SagaResponse> list() {
        return repository.findAllByOrderByUpdatedAtDesc().stream()
                .map(SagaResponse::from)
                .toList();
    }

    @GetMapping("/{paymentId}")
    public SagaResponse get(@PathVariable UUID paymentId) {
        return repository.findById(paymentId)
                .map(SagaResponse::from)
                .orElseThrow(() -> new SagaNotFoundException(paymentId));
    }

    @ExceptionHandler(SagaNotFoundException.class)
    public ResponseEntity<String> handleNotFound(SagaNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }
}
