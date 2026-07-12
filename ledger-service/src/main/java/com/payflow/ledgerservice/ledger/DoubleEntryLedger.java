package com.payflow.ledgerservice.ledger;

import com.payflow.ledgerservice.domain.LedgerEntry;
import com.payflow.ledgerservice.domain.PostingType;
import com.payflow.ledgerservice.repository.LedgerEntryRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Posts the HOLD leg of a payment: debit payer, credit the suspense
 * account. A real settlement-service (later phase) would post the
 * matching FINAL leg — debit suspense, credit payee — to complete the
 * double entry. Every posting is a new row; nothing here is ever updated.
 */
@Component
public class DoubleEntryLedger {

    // A fixed system account standing in for "funds in transit" between
    // authorization and capture — not owned by any payer or payee.
    public static final UUID SUSPENSE_ACCOUNT =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final LedgerEntryRepository ledgerEntryRepository;

    public DoubleEntryLedger(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    public void postHold(UUID paymentId, UUID payerAccount, long amountCents) {
        if (ledgerEntryRepository.existsByPaymentIdAndPostingType(paymentId, PostingType.HOLD)) {
            return; // already posted — safe no-op on redelivery
        }
        ledgerEntryRepository.save(new LedgerEntry(
                UUID.randomUUID(), paymentId, payerAccount, SUSPENSE_ACCOUNT,
                amountCents, PostingType.HOLD, Instant.now()));
    }
}
