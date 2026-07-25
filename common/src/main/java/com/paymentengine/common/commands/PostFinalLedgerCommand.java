package com.payflow.common.commands;

import java.time.Instant;
import java.util.UUID;

/**
 * Converts the HOLD leg into a FINAL one: debit the suspense account,
 * credit the payee. Issued after settlement confirms capture -- a
 * bookkeeping step that doesn't gate the payment's terminal state.
 */
public record PostFinalLedgerCommand(
        UUID paymentId,
        UUID payeeAccount,
        long amountCents,
        Instant occurredAt
) {
}
