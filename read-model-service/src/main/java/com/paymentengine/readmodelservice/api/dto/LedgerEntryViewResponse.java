package com.payflow.readmodelservice.api.dto;

import com.payflow.readmodelservice.domain.LedgerEntryView;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntryViewResponse(
        UUID id,
        UUID paymentId,
        UUID debitAccount,
        UUID creditAccount,
        long amountCents,
        String postingType,
        Instant postedAt
) {
    public static LedgerEntryViewResponse from(LedgerEntryView entry) {
        return new LedgerEntryViewResponse(
                entry.getId(),
                entry.getPaymentId(),
                entry.getDebitAccount(),
                entry.getCreditAccount(),
                entry.getAmountCents(),
                entry.getPostingType(),
                entry.getPostedAt()
        );
    }
}
