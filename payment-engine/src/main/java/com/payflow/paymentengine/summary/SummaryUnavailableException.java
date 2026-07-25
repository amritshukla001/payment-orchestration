package com.payflow.paymentengine.summary;

import java.util.UUID;

/**
 * A summary can't be produced for this payment: either the payment is
 * unknown (maps to 404) or it isn't in a summarizable state (maps to 409).
 */
public class SummaryUnavailableException extends RuntimeException {

    private final UUID paymentId;
    private final boolean notFound;

    public SummaryUnavailableException(UUID paymentId, String message, boolean notFound) {
        super(message);
        this.paymentId = paymentId;
        this.notFound = notFound;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public boolean isNotFound() {
        return notFound;
    }
}
