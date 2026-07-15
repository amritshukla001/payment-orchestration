package com.payflow.ledgerservice.ledger;

import com.payflow.ledgerservice.domain.LedgerEntry;
import com.payflow.ledgerservice.domain.PostingType;
import com.payflow.ledgerservice.repository.LedgerEntryRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Posts both legs of a payment's double entry: HOLD (debit payer, credit
 * suspense) at authorization time, FINAL (debit suspense, credit payee)
 * once settlement confirms capture. Every posting is a new row; nothing
 * here is ever updated.
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

    public void postFinal(UUID paymentId, UUID payeeAccount, long amountCents) {
        if (ledgerEntryRepository.existsByPaymentIdAndPostingType(paymentId, PostingType.FINAL)) {
            return; // already posted — safe no-op on redelivery
        }
        ledgerEntryRepository.save(new LedgerEntry(
                UUID.randomUUID(), paymentId, SUSPENSE_ACCOUNT, payeeAccount,
                amountCents, PostingType.FINAL, Instant.now()));
    }

    public void reverseHold(UUID paymentId, UUID payerAccount, long amountCents) {
        if (ledgerEntryRepository.existsByPaymentIdAndPostingType(paymentId, PostingType.REVERSAL)) {
            return; // already posted — safe no-op on redelivery
        }
        ledgerEntryRepository.save(new LedgerEntry(
                UUID.randomUUID(), paymentId, SUSPENSE_ACCOUNT, payerAccount,
                amountCents, PostingType.REVERSAL, Instant.now()));
    }
}
