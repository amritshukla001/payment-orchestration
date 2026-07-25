package com.payflow.paymentengine.api;

import java.util.UUID;

public class PaymentEngineNotFoundException extends RuntimeException {
    public PaymentEngineNotFoundException(UUID paymentId) {
        super("No payment found for " + paymentId);
    }
}
