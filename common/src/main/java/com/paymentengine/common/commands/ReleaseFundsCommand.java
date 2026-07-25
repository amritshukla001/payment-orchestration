package com.payflow.common.commands;

import java.time.Instant;
import java.util.UUID;

/**
 * Compensation command — releases a funds reservation that was already
 * authorized. Issued by the orchestrator after the ledger's HOLD leg has
 * been reversed, undoing the saga's steps in reverse order of how they
 * were originally applied.
 */
public record ReleaseFundsCommand(
        UUID paymentId,
        UUID payerAccount,
        long amountCents,
        Instant occurredAt
) {
}
