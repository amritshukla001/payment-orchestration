package com.payflow.ledgerservice.api;

import com.payflow.ledgerservice.api.dto.LedgerEntryResponse;
import com.payflow.ledgerservice.repository.LedgerEntryRepository;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Read-only view onto the double-entry ledger for the dashboard. */
@RestController
@RequestMapping("/api/ledger")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:4173"})
public class LedgerController {

    private final LedgerEntryRepository repository;

    public LedgerController(LedgerEntryRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{paymentId}")
    public List<LedgerEntryResponse> byPayment(@PathVariable UUID paymentId) {
        return repository.findByPaymentIdOrderByPostedAtAsc(paymentId).stream()
                .map(LedgerEntryResponse::from)
                .toList();
    }
}
