package com.payflow.common.commands;

import java.time.Instant;
import java.util.UUID;

/**
 * Compensation command — not issued anywhere yet. The orchestrator's
 * failure/rollback path is a later phase; this defines the contract
 * funds-auth-service already implements a handler for.
 */
public record ReleaseFundsCommand(
        UUID paymentId,
        UUID payerAccount,
        long amountCents,
        Instant occurredAt
) {
}
