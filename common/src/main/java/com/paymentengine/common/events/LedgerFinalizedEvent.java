package com.paymentengine.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Carries the full posted entry, not just paymentId -- see LedgerPostedEvent.
 */
public record LedgerFinalizedEvent(
        UUID id,
        UUID paymentId,
        UUID debitAccount,
        UUID creditAccount,
        long amountCents,
        String postingType,
        Instant occurredAt
) {
}
