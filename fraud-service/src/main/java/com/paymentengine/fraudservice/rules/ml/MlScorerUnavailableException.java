package com.paymentengine.fraudservice.rules.ml;

public class MlScorerUnavailableException extends RuntimeException {
    public MlScorerUnavailableException(String message) {
        super(message);
    }
}
