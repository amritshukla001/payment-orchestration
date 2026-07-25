package com.paymentengine.ledgerservice.api;

import com.paymentengine.ledgerservice.api.dto.LedgerEntryResponse;
import com.paymentengine.ledgerservice.repository.LedgerEntryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Read-only view onto the double-entry ledger for the dashboard. */
@RestController
@RequestMapping("/api/ledger")
@Tag(name = "Ledger", description = "Append-only double-entry postings")
public class LedgerController {

    private final LedgerEntryRepository repository;

    public LedgerController(LedgerEntryRepository repository) {
        this.repository = repository;
    }

    @Operation(summary = "List a payment's ledger postings",
            description = "Every posting for the payment in order: HOLD (payer -> suspense), FINAL "
                    + "(suspense -> payee) on capture, or REVERSAL (suspense -> payer) on compensation.")
    @GetMapping("/{paymentId}")
    public List<LedgerEntryResponse> byPayment(@PathVariable UUID paymentId) {
        return repository.findByPaymentIdOrderByPostedAtAsc(paymentId).stream()
                .map(LedgerEntryResponse::from)
                .toList();
    }
}
