package com.payflow.common.commands;

import java.time.Instant;
import java.util.UUID;

/**
 * Undoes a previously-posted HOLD leg: debit the suspense account, credit
 * the payer back. Issued when settlement declines after funds were
 * already authorized -- the ledger is append-only, so this is a new
 * offsetting entry, never a mutation of the original HOLD row.
 */
public record ReverseLedgerCommand(
        UUID paymentId,
        UUID payerAccount,
        long amountCents,
        Instant occurredAt
) {
}
