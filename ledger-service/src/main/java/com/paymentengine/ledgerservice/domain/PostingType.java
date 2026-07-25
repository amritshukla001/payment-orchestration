package com.payflow.ledgerservice.domain;

/**
 * HOLD is posted when funds move from payer to the suspense account
 * during authorization. FINAL moves them from suspense to payee,
 * completing the capture. REVERSAL is the compensating entry when
 * settlement declines after a HOLD was already posted -- it undoes the
 * HOLD (debit suspense, credit payer) as a new offsetting row, never by
 * editing the original.
 */
public enum PostingType {
    HOLD,
    FINAL,
    REVERSAL
}
