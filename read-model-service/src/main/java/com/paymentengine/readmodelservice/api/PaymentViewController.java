package com.paymentengine.readmodelservice.api;

import com.paymentengine.readmodelservice.api.dto.LedgerEntryViewResponse;
import com.paymentengine.readmodelservice.api.dto.NotificationViewResponse;
import com.paymentengine.readmodelservice.api.dto.PaymentDetailResponse;
import com.paymentengine.readmodelservice.api.dto.PaymentViewResponse;
import com.paymentengine.readmodelservice.domain.PaymentView;
import com.paymentengine.readmodelservice.repository.LedgerEntryViewRepository;
import com.paymentengine.readmodelservice.repository.NotificationViewRepository;
import com.paymentengine.readmodelservice.repository.PaymentViewRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The CQRS read side for the dashboard: a single denormalized view, built
 * entirely from payment.events by ProjectionEventListener, replacing three
 * separate calls into saga-orchestrator/ledger-service/notification-service.
 */
@RestController
@RequestMapping("/api/payments")
@Tag(name = "Payments (read model)", description = "Denormalized CQRS projection built from payment.events")
public class PaymentViewController {

    private final PaymentViewRepository paymentViewRepository;
    private final LedgerEntryViewRepository ledgerEntryViewRepository;
    private final NotificationViewRepository notificationViewRepository;

    public PaymentViewController(PaymentViewRepository paymentViewRepository,
                                  LedgerEntryViewRepository ledgerEntryViewRepository,
                                  NotificationViewRepository notificationViewRepository) {
        this.paymentViewRepository = paymentViewRepository;
        this.ledgerEntryViewRepository = ledgerEntryViewRepository;
        this.notificationViewRepository = notificationViewRepository;
    }

    @Operation(summary = "List all payments", description = "Every payment's current state, most recently updated first — the dashboard grid.")
    @GetMapping
    public List<PaymentViewResponse> list() {
        return paymentViewRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(PaymentViewResponse::from)
                .toList();
    }

    @Operation(summary = "Get a payment's full detail",
            description = "One response bundling the payment's state, ledger postings, and notifications — the dashboard's detail drawer.")
    @GetMapping("/{paymentId}")
    public PaymentDetailResponse get(@PathVariable UUID paymentId) {
        PaymentView view = paymentViewRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentViewNotFoundException(paymentId));

        List<LedgerEntryViewResponse> ledgerEntries = ledgerEntryViewRepository
                .findByPaymentIdOrderByPostedAtAsc(paymentId).stream()
                .map(LedgerEntryViewResponse::from)
                .toList();

        List<NotificationViewResponse> notifications = notificationViewRepository
                .findByPaymentIdOrderBySentAtAsc(paymentId).stream()
                .map(NotificationViewResponse::from)
                .toList();

        return new PaymentDetailResponse(PaymentViewResponse.from(view), ledgerEntries, notifications);
    }

    @ExceptionHandler(PaymentViewNotFoundException.class)
    public ResponseEntity<String> handleNotFound(PaymentViewNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }
}
