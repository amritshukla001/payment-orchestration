package com.payflow.fraudservice.rules.ml;

public class MlScorerUnavailableException extends RuntimeException {
    public MlScorerUnavailableException(String message) {
        super(message);
    }
}
