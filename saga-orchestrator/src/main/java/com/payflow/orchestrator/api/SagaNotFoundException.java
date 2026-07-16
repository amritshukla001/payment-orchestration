package com.payflow.orchestrator.api;

import java.util.UUID;

public class SagaNotFoundException extends RuntimeException {
    public SagaNotFoundException(UUID paymentId) {
        super("No saga found for payment " + paymentId);
    }
}
