package com.paymentengine.readmodelservice.api;

import java.util.UUID;

public class PaymentViewNotFoundException extends RuntimeException {
    public PaymentViewNotFoundException(UUID paymentId) {
        super("No payment view found for payment " + paymentId);
    }
}
