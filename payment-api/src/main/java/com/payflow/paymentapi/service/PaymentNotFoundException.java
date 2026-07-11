package com.payflow.paymentapi.service;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(UUID id) {
        super("No payment found with id " + id);
    }
}
