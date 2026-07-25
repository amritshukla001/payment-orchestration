package com.payflow.ledgerservice.api.dto;

import com.payflow.ledgerservice.domain.LedgerEntry;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID id,
        UUID paymentId,
        UUID debitAccount,
        UUID creditAccount,
        long amountCents,
        String postingType,
        Instant postedAt
) {
    public static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getPaymentId(),
                entry.getDebitAccount(),
                entry.getCreditAccount(),
                entry.getAmountCents(),
                entry.getPostingType().name(),
                entry.getPostedAt()
        );
    }
}
