package com.payflow.readmodelservice.api;

import com.payflow.readmodelservice.api.dto.LedgerEntryViewResponse;
import com.payflow.readmodelservice.api.dto.NotificationViewResponse;
import com.payflow.readmodelservice.api.dto.PaymentDetailResponse;
import com.payflow.readmodelservice.api.dto.PaymentViewResponse;
import com.payflow.readmodelservice.domain.PaymentView;
import com.payflow.readmodelservice.repository.LedgerEntryViewRepository;
import com.payflow.readmodelservice.repository.NotificationViewRepository;
import com.payflow.readmodelservice.repository.PaymentViewRepository;
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

    @GetMapping
    public List<PaymentViewResponse> list() {
        return paymentViewRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(PaymentViewResponse::from)
                .toList();
    }

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
