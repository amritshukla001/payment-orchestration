package com.payflow.ledgerservice.domain;

/**
 * HOLD is posted when funds move from payer to the suspense account
 * during authorization. FINAL (posted by settlement-service, a later
 * phase) moves them from suspense to payee, completing the capture.
 */
public enum PostingType {
    HOLD,
    FINAL
}
