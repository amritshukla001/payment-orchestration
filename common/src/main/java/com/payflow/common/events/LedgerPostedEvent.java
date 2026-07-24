package com.payflow.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Carries the full posted entry, not just paymentId -- the orchestrator
 * only cares that this arrived, but a denormalized read model needs the
 * actual debit/credit accounts and amount to reconstruct the posting
 * without calling back into ledger-service.
 */
public record LedgerPostedEvent(
        UUID id,
        UUID paymentId,
        UUID debitAccount,
        UUID creditAccount,
        long amountCents,
        String postingType,
        Instant occurredAt
) {
}
