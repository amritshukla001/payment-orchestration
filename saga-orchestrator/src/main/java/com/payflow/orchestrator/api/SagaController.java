package com.payflow.orchestrator.api;

import com.payflow.orchestrator.api.dto.SagaResponse;
import com.payflow.orchestrator.api.dto.SagaSummaryResponse;
import com.payflow.orchestrator.domain.SagaEventStore;
import com.payflow.orchestrator.summary.CompensationSummaryService;
import com.payflow.orchestrator.summary.SummaryUnavailableException;
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
public class SagaController {

    private final SagaEventStore sagaEventStore;
    private final CompensationSummaryService compensationSummaryService;

    public SagaController(SagaEventStore sagaEventStore,
                           CompensationSummaryService compensationSummaryService) {
        this.sagaEventStore = sagaEventStore;
        this.compensationSummaryService = compensationSummaryService;
    }

    @GetMapping
    public List<SagaResponse> list() {
        return sagaEventStore.loadAll().stream()
                .map(SagaResponse::from)
                .toList();
    }

    @GetMapping("/{paymentId}")
    public SagaResponse get(@PathVariable UUID paymentId) {
        return sagaEventStore.load(paymentId)
                .map(SagaResponse::from)
                .orElseThrow(() -> new SagaNotFoundException(paymentId));
    }

    /**
     * On-demand AI incident summary for a COMPENSATED payment -- the LLM
     * explains an already-final saga history, it never decides anything.
     * Generated once and cached; degrades to a deterministic template when
     * the Claude API is unavailable (see CompensationSummaryService).
     */
    @GetMapping("/{paymentId}/summary")
    public SagaSummaryResponse summary(@PathVariable UUID paymentId) {
        return SagaSummaryResponse.from(compensationSummaryService.summarize(paymentId));
    }

    @ExceptionHandler(SagaNotFoundException.class)
    public ResponseEntity<String> handleNotFound(SagaNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    @ExceptionHandler(SummaryUnavailableException.class)
    public ResponseEntity<String> handleSummaryUnavailable(SummaryUnavailableException e) {
        return ResponseEntity
                .status(e.isNotFound() ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT)
                .body(e.getMessage());
    }
}
