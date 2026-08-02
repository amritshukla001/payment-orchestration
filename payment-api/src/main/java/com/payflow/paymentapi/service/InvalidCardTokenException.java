package com.payflow.paymentapi.service;

public class InvalidCardTokenException extends RuntimeException {
    public InvalidCardTokenException(String message) {
        super(message);
    }
}
