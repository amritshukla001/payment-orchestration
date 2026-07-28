package com.payflow.paymentengine.api;

import com.payflow.common.enums.PaymentState;

import java.util.UUID;

public class StepUpNotPendingException extends RuntimeException {
    public StepUpNotPendingException(UUID paymentId, PaymentState actualState) {
        super("Payment " + paymentId + " is not awaiting a step-up confirmation (current state: " + actualState + ")");
    }
}
